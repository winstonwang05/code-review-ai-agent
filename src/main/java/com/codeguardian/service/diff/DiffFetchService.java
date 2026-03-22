package com.codeguardian.service.diff;

import com.codeguardian.service.diff.model.ChangeType;
import com.codeguardian.service.diff.model.FileDiff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Diff 拉取服务
 * 通过 GitCode REST API 拉取 MR 变更文件列表和完整源码
 * 不 clone 仓库，内存操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiffFetchService {

    @Value("${gitcode.api.base-url:https://api.gitcode.com/api/v5}")
    private String baseUrl;

    @Value("${gitcode.token:}")
    private String token;

    private final RestClient.Builder restClientBuilder;

    /**
     * 拉取 MR 的所有变更文件
     * GET /projects/{encodedPath}/merge_requests/{mrIid}/diffs
     *
     * @param headSha MR 的 head commit sha（来自 Webhook Payload last_commit.id / CicdMessage.commitHash）
     *                用于精确拉取新版本源码，避免用 HEAD 造成脏数据
     */
    public List<FileDiff> fetchMrDiffs(String gitUrl, int mrIid, String headSha) {
        List<FileDiff> result = new ArrayList<>();
        try {
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);

            // 1. 拉取 MR diff 列表，同时获取 base_commit_sha 和 head_commit_sha
            String uri = String.format("/projects/%s/merge_requests/%d/diffs", encodedPath, mrIid);
            List<Map<String, Object>> diffs = restClientBuilder.baseUrl(baseUrl).build()
                    .get().uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (diffs == null || diffs.isEmpty()) {
                log.warn("[DiffFetch] MR#{} 无变更文件", mrIid);
                return result;
            }

            // 2. 拉取 MR 详情，获取精确的 base_commit_sha（merge base）
            String mrDetailUri = String.format("/projects/%s/merge_requests/%d", encodedPath, mrIid);
            Map<String, Object> mrDetail = restClientBuilder.baseUrl(baseUrl).build()
                    .get().uri(mrDetailUri)
                    .header("PRIVATE-TOKEN", token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            // diff_refs 包含 base_sha（merge base）、head_sha、start_sha
            String baseSha = null;
            String resolvedHeadSha = headSha;
            if (mrDetail != null) {
                Map<String, Object> diffRefs = (Map<String, Object>) mrDetail.get("diff_refs");
                if (diffRefs != null) {
                    baseSha          = (String) diffRefs.get("base_sha");
                    resolvedHeadSha  = (String) diffRefs.getOrDefault("head_sha", headSha);
                }
            }
            log.info("[DiffFetch] MR#{} baseSha={}, headSha={}", mrIid, baseSha, resolvedHeadSha);

            // 3. 遍历每个变更文件，用精确 sha 拉取源码
            for (Map<String, Object> diff : diffs) {
                String filePath = (String) diff.get("new_path");
                if (filePath == null) filePath = (String) diff.get("old_path");
                String diffContent    = (String) diff.getOrDefault("diff", "");
                boolean isNewFile     = Boolean.TRUE.equals(diff.get("new_file"));
                boolean isDeletedFile = Boolean.TRUE.equals(diff.get("deleted_file"));

                ChangeType changeType = isNewFile ? ChangeType.ADD
                        : isDeletedFile ? ChangeType.FULL_DELETE
                        : ChangeType.MODIFY;

                String oldPath = (String) diff.get("old_path");
                String newPath = (String) diff.get("new_path");

                String newContent = null;
                String oldContent = null;

                // 新版本源码：用 headSha，精确到本次 MR 的目标 commit，避免用 HEAD 拉到后续提交的代码
                if (!isDeletedFile && newPath != null) {
                    newContent = fetchFileContent(encodedPath, newPath, resolvedHeadSha);
                }
                // 旧版本源码：用 baseSha（merge base），精确到 MR 分叉点，避免用 HEAD~1 随主干提交漂移
                if (!isNewFile && oldPath != null) {
                    String ref = baseSha != null ? baseSha : "HEAD~1";
                    oldContent = fetchFileContent(encodedPath, oldPath, ref);
                }

                result.add(FileDiff.builder()
                        .filePath(filePath)
                        .changeType(changeType)
                        .diffContent(diffContent)
                        .oldContent(oldContent)
                        .newContent(newContent)
                        .language(detectLanguage(filePath))
                        .build());
            }

            log.info("[DiffFetch] MR#{} 拉取完成，变更文件数={}", mrIid, result.size());
        } catch (Exception e) {
            log.error("[DiffFetch] 拉取 MR#{} Diff 失败: {}", mrIid, e.getMessage(), e);
        }
        return result;
    }

    /**
     * 拉取单个文件完整内容
     * GET /projects/{encodedPath}/repository/files/{encodedFilePath}/raw?ref={ref}
     */
    private String fetchFileContent(String encodedProjectPath, String filePath, String ref) {
        try {
            String encodedFilePath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
            String uri = String.format("/projects/%s/repository/files/%s/raw?ref=%s",
                    encodedProjectPath, encodedFilePath, ref);
            return restClientBuilder.baseUrl(baseUrl).build()
                    .get().uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("[DiffFetch] 拉取文件内容失败: path={}, ref={}, err={}", filePath, ref, e.getMessage());
            return null;
        }
    }

    /**
     * 从文件扩展名推断语言
     */
    public String detectLanguage(String filePath) {
        if (filePath == null) return "unknown";
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java"))  return "Java";
        if (lower.endsWith(".py"))    return "Python";
        if (lower.endsWith(".js"))    return "JavaScript";
        if (lower.endsWith(".ts"))    return "TypeScript";
        if (lower.endsWith(".go"))    return "Go";
        if (lower.endsWith(".rs"))    return "Rust";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".c")) return "C/C++";
        if (lower.endsWith(".cs"))    return "C#";
        if (lower.endsWith(".kt"))    return "Kotlin";
        if (lower.endsWith(".rb"))    return "Ruby";
        if (lower.endsWith(".php"))   return "PHP";
        if (lower.endsWith(".swift")) return "Swift";
        return "unknown";
    }

    /**
     * 从 gitUrl 提取 projectPath（owner/repo）
     */
    public String extractProjectPath(String gitUrl) {
        if (gitUrl == null) return "";
        String clean = gitUrl.replace(".git", "");
        if (clean.startsWith("http")) {
            int idx = clean.indexOf("/", clean.indexOf("://") + 3);
            if (idx != -1) return clean.substring(idx + 1);
        }
        return clean;
    }
}
