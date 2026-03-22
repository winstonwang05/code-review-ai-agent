package com.codeguardian.service.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * @description: Git平台反馈服务
 * 用于向GitCode发送评论和更新状态
 * @author: Winston
 * @date: 2026/3/12 16:04
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitFeedbackService {
    /**
     * Git平台的API的URL（面向程序）
     */
    @Value("${gitcode.api.base-url:https://api.gitcode.com/api/v5}")
    private String baseUrl;

    @Value("${gitcode.token:}")
    private String token;
    /**
     * 用来发送HTTP请求
     */
    private final RestClient.Builder restClientBuilder;

    /**
     * 发表评论
     * @param gitUrl 获取文件名
     * @param prNumber 文件号
     * @param comment 需要发送的评论
     */
    public void postComment(String gitUrl, String prNumber, String comment) {

        if (token == null || token.isEmpty()) {
            log.warn("未配置 GitCode Token，跳过发送评论。");
            log.info("模拟发送评论到 {} #{}: {}", gitUrl, prNumber, comment);
            return;
        }
        try {

            // 1.拼接请求体
            String projectPath = extractProjectPath(gitUrl);
            // 需要将文件路径编码
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);

            String uri = String.format("/projects/%s/merge_requests/%s/notes", encodedPath, prNumber);
            log.info("正在向 GitCode 发送评论: {}", uri);
            // 2.构建发送
            restClientBuilder.baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", comment))
                    .retrieve()
                    .toBodilessEntity();
            log.info("成功向 GitCode 发送评论");
        } catch (Exception e) {
            log.error("向 GitCode 发送评论失败: {}", e.getMessage());
        }
    }

    /**
     * 更新状态
     * @param gitUrl git链接
     * @param commitHash 上传的随机哈希
     * @param state 需要更新的状态
     * @param description 描述更新
     */
    public void updateStatus(String gitUrl, String commitHash, String state, String description) {
        if (token == null || token.isEmpty()) {
            log.warn("未配置 GitCode Token，跳过状态更新。");
            log.info("模拟更新状态到 {} {}: {} - {}", gitUrl, commitHash, state, description);
            return;
        }
        try {
            // 1.拼接请求体
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);

            // 状态映射: pending, running, success, failed, canceled
            String gitLabState = mapToGitLabState(state);

            String uri = String.format("/projects/%s/statuses/%s", encodedPath, commitHash);
            // 2.构建发送
            restClientBuilder.baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "state", gitLabState,
                            "description", description,
                            "context", "CodeGuardian AI"
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("成功更新 GitCode 状态");
        } catch (Exception e) {
            log.error("更新 GitCode 状态失败: {}", e.getMessage());
        }
    }

    /**
     * 幂等检查：查询 MR 评论列表，判断是否已存在包含指定关键词的评论
     * 用于防止 ACK 失败重投后重复写评论
     *
     * @param gitUrl    仓库 URL
     * @param prNumber  MR IID
     * @param keyword   评论关键词（如 "CodeGuardian AI 审查报告"）
     * @return true=已存在，false=不存在或查询失败
     */
    public boolean hasComment(String gitUrl, String prNumber, String keyword) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String uri = String.format("/projects/%s/merge_requests/%s/notes?per_page=20", encodedPath, prNumber);

            // 返回评论列表，每条评论包含 body 字段
            List<Map<String, Object>> notes = restClientBuilder.baseUrl(baseUrl)
                    .build()
                    .get()
                    .uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {});

            if (notes == null) return false;

            return notes.stream()
                    .anyMatch(note -> {
                        Object body = note.get("body");
                        return body instanceof String s && s.contains(keyword);
                    });
        } catch (Exception e) {
            log.warn("查询 GitCode 评论列表失败，默认返回 false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 截取文件名，拼接得到GitLab的API
     */
    private String extractProjectPath(String gitUrl) {
        // 示例: https://gitcode.com/owner/repo.git -> owner/repo
        String cleanUrl = gitUrl.replace(".git", "");
        if (cleanUrl.startsWith("http")) {
            int pathIndex = cleanUrl.indexOf("/", cleanUrl.indexOf("://") + 3);
            if (pathIndex != -1) {
                return cleanUrl.substring(pathIndex + 1);
            }
        }
        return cleanUrl;
    }

    /**
     * 转化为GitLab状态
     * @param state 状态
     * @return 返回映射状态
     */
    private String mapToGitLabState(String state) {
        return switch (state.toLowerCase()) {
            case "success", "passed" -> "success";
            case "failure", "failed", "error" -> "failed";
            case "pending", "running" -> "pending";
            default -> "pending";
        };
    }
}
