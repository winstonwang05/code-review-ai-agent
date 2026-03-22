package com.codeguardian.service.diff;

import com.codeguardian.service.diff.model.*;
import com.codeguardian.service.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 核心变更分析器
 * 职责：FileDiff 列表 → ChangeUnit 列表
 *
 * 分流决策（ADD/MODIFY）：
 *   交集方法为空           → FILE_LEVEL（成员变量/包声明等）
 *   方法数 > 5 且变化行比例 > 30%  → FILE_LEVEL（爆炸式变更，无指纹）
 *   否则                   → 每方法独立 METHOD_LEVEL（独立指纹）
 *
 * DELETE：
 *   无方法 / 方法数 > 5    → FILE_LEVEL，无指纹
 *   方法数 ≤ 5             → 每方法 METHOD_LEVEL，无指纹（删除不缓存）
 *
 * RAG query 三模板（均拼入 prDescription 提升召回率）：
 *   METHOD_LEVEL  → className + methodSignature + prDescription + 方法体前200字
 *   FILE_LEVEL有方法 → className + 方法签名列表 + prDescription
 *   FILE_LEVEL无方法 → filePath + prDescription + diff前300字
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeAnalyzer {

    private final AstParserService astParserService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final SemanticFingerprintService fingerprintService;

    /** 方法数超过此值 AND 变化行比例超过此值时，走整体路径 */
    private static final int    METHOD_COUNT_THRESHOLD = 5;
    private static final double CHANGE_RATIO_THRESHOLD = 0.3;

    /** 从 diff 文本中提取被删除词段（用于 FULL_DELETE 的 RAG query） */
    private static final Pattern DELETED_WORD_PATTERN = Pattern.compile("^-\\s*(.+)$", Pattern.MULTILINE);

    /**
     * 核心入口：FileDiff 列表 → ChangeUnit 列表
     * @param prDescription PR 描述 / commit message，注入 RAG query 提升召回率
     */
    public List<ChangeUnit> analyze(List<FileDiff> diffs, String prDescription) {
        String desc = prDescription != null ? prDescription : "";
        List<ChangeUnit> units = new ArrayList<>();
        for (FileDiff diff : diffs) {
            try {
                units.addAll(analyzeFile(diff, desc));
            } catch (Exception e) {
                log.error("[ChangeAnalyzer] 分析文件失败: path={}, err={}", diff.getFilePath(), e.getMessage(), e);
            }
        }
        log.info("[ChangeAnalyzer] 分析完成，共 {} 个 ChangeUnit", units.size());
        return units;
    }

    // ---- 文件级分发 ----

    private List<ChangeUnit> analyzeFile(FileDiff diff, String prDescription) {
        return switch (diff.getChangeType()) {
            case ADD         -> handleAddOrModify(diff, diff.getNewContent(), prDescription);
            case MODIFY      -> handleAddOrModify(diff, diff.getNewContent(), prDescription);
            case FULL_DELETE -> handleDelete(diff, prDescription);
            default          -> Collections.emptyList();
        };
    }

    /**
     * ADD / MODIFY 统一处理（取新代码）
     */
    private List<ChangeUnit> handleAddOrModify(FileDiff diff, String source, String prDescription) {
        if (source == null || source.isBlank()) return Collections.emptyList();

        List<int[]> hunkRanges   = astParserService.parseHunkRanges(diff.getDiffContent());
        List<MethodNode> allMethods     = parseMethods(source, diff.getLanguage());
        List<MethodNode> changedMethods = diff.getChangeType() == ChangeType.ADD
                ? allMethods
                : astParserService.findChangedMethods(allMethods, hunkRanges);

        int changedLines = countChangedLines(diff.getDiffContent());
        int totalLines   = source.split("\n").length;
        double ratio     = totalLines > 0 ? (double) changedLines / totalLines : 1.0;

        // 交集为空：成员变量 / 包声明 / 注解 → FILE_LEVEL
        if (changedMethods.isEmpty()) {
            RiskLevel risk = calcRiskByLines(changedLines);
            RagContext rag = retrieveRagFileLevel(null, diff.getDiffContent(), diff.getLanguage(), diff.getFilePath(), prDescription);
            return List.of(ChangeUnit.builder()
                    .filePath(diff.getFilePath()).changeType(diff.getChangeType())
                    .method(null).codeToReview(diff.getDiffContent()).diffSnippet(diff.getDiffContent())
                    .language(diff.getLanguage()).ragContext(rag).semanticKey(null)
                    .reviewScope(ReviewScope.FILE_LEVEL).riskLevel(risk)
                    .prDescription(prDescription).allChangedMethods(Collections.emptyList())
                    .build());
        }

        // 爆炸式变更：方法数 > 5 且变化行比例 > 30% → FILE_LEVEL
        boolean isExplosive = changedMethods.size() > METHOD_COUNT_THRESHOLD && ratio > CHANGE_RATIO_THRESHOLD;
        if (isExplosive) {
            RagContext rag = retrieveRagFileLevel(changedMethods, diff.getDiffContent(), diff.getLanguage(), diff.getFilePath(), prDescription);
            return List.of(ChangeUnit.builder()
                    .filePath(diff.getFilePath()).changeType(diff.getChangeType())
                    .method(null).codeToReview(diff.getDiffContent()).diffSnippet(diff.getDiffContent())
                    .language(diff.getLanguage()).ragContext(rag).semanticKey(null)
                    .reviewScope(ReviewScope.FILE_LEVEL).riskLevel(RiskLevel.HIGH)
                    .prDescription(prDescription).allChangedMethods(changedMethods)
                    .build());
        }

        // 常规变更：每方法独立 METHOD_LEVEL
        List<ChangeUnit> units = new ArrayList<>();
        for (MethodNode method : changedMethods) {
            String diffSnippet  = extractDiffSnippetForMethod(diff.getDiffContent(), method);
            String stripped     = astParserService.stripCommentsAndWhitespace(method.getBody());
            String fingerKey    = fingerprintService.computeKey(stripped, diff.getLanguage());
            Optional<List<com.codeguardian.entity.Finding>> cached = fingerprintService.getFromCache(fingerKey);

            RagContext rag;
            if (cached.isPresent()) {
                rag = RagContext.builder().fromCache(true).cachedFindings(cached.get())
                        .snippets(Collections.emptyList()).build();
            } else {
                rag = retrieveRagMethodLevel(method, method.getBody(), diff.getLanguage(), prDescription);
            }

            RiskLevel risk = calcRiskByMethodLines(method);
            units.add(ChangeUnit.builder()
                    .filePath(diff.getFilePath()).changeType(diff.getChangeType())
                    .method(method).codeToReview(method.getBody()).diffSnippet(diffSnippet)
                    .language(diff.getLanguage()).ragContext(rag).semanticKey(fingerKey)
                    .reviewScope(ReviewScope.METHOD_LEVEL).riskLevel(risk)
                    .prDescription(prDescription).allChangedMethods(Collections.emptyList())
                    .build());
        }
        return units;
    }

    /**
     * FULL_DELETE 处理
     */
    private List<ChangeUnit> handleDelete(FileDiff diff, String prDescription) {
        String source = diff.getOldContent();

        if (source == null || source.isBlank()) {
            RagContext rag = retrieveRagDiff(diff.getDiffContent(), diff.getLanguage());
            return List.of(buildDeleteUnit(diff, null, diff.getDiffContent(), ReviewScope.FILE_LEVEL,
                    RiskLevel.HIGH, rag, Collections.emptyList(), prDescription));
        }

        List<int[]> hunkRanges           = astParserService.parseHunkRanges(diff.getDiffContent());
        List<MethodNode> allMethods       = parseMethods(source, diff.getLanguage());
        List<MethodNode> affectedMethods  = astParserService.findChangedMethods(allMethods, hunkRanges);

        if (affectedMethods.isEmpty() || affectedMethods.size() > METHOD_COUNT_THRESHOLD) {
            RagContext rag = retrieveRagFileLevel(affectedMethods.isEmpty() ? null : affectedMethods,
                    diff.getDiffContent(), diff.getLanguage(), diff.getFilePath(), prDescription);
            return List.of(buildDeleteUnit(diff, null, diff.getDiffContent(), ReviewScope.FILE_LEVEL,
                    RiskLevel.HIGH, rag, affectedMethods, prDescription));
        }

        // 方法数 ≤ 5：每方法独立（不做指纹，删除无缓存意义）
        List<ChangeUnit> units = new ArrayList<>();
        for (MethodNode method : affectedMethods) {
            String diffSnippet   = extractDiffSnippetForMethod(diff.getDiffContent(), method);
            boolean isFullDelete = isEntireMethodDeleted(diffSnippet, method);
            ChangeType type      = isFullDelete ? ChangeType.FULL_DELETE : ChangeType.PARTIAL_DELETE;
            RagContext rag       = retrieveRagMethodLevel(method, method.getBody(), diff.getLanguage(), prDescription);
            ChangeUnit unit = buildDeleteUnit(diff, method, diffSnippet, ReviewScope.METHOD_LEVEL,
                    calcRiskByMethodLines(method), rag, Collections.emptyList(), prDescription);
            unit.setChangeType(type);
            units.add(unit);
        }
        return units;
    }

    // ---- ChangeUnit 构建辅助 ----

    private ChangeUnit buildDeleteUnit(FileDiff diff, MethodNode method, String diffSnippet,
                                       ReviewScope scope, RiskLevel risk, RagContext rag,
                                       List<MethodNode> allChangedMethods, String prDescription) {
        String code = method != null ? method.getBody() : diffSnippet;
        return ChangeUnit.builder()
                .filePath(diff.getFilePath()).changeType(ChangeType.FULL_DELETE)
                .method(method).codeToReview(code).diffSnippet(diffSnippet)
                .language(diff.getLanguage()).ragContext(rag).semanticKey(null)
                .reviewScope(scope).riskLevel(risk)
                .prDescription(prDescription).allChangedMethods(allChangedMethods)
                .build();
    }

    // ---- RAG 检索（三模板）----

    /** METHOD_LEVEL：className + methodSignature + prDescription + 方法体前200字 */
    private RagContext retrieveRagMethodLevel(MethodNode method, String codeToReview, String language, String prDescription) {
        try {
            String methodName = method != null ? method.getMethodName() : "";
            String className  = method != null ? method.getClassName()  : "";
            String snippet    = codeToReview != null
                    ? codeToReview.substring(0, Math.min(codeToReview.length(), 200)) : "";
            String query = String.format("Language: %s\nClass: %s\nMethod: %s\nIntent: %s\n%s",
                    language, className, methodName, prDescription, snippet);
            return RagContext.builder().snippets(knowledgeBaseService.searchSnippets(query, 3))
                    .fromCache(false).build();
        } catch (Exception e) {
            log.warn("[ChangeAnalyzer] METHOD RAG 失败: {}", e.getMessage());
            return RagContext.builder().snippets(Collections.emptyList()).fromCache(false).build();
        }
    }

    /** FILE_LEVEL：有方法 → className + 方法签名列表 + prDescription；无方法 → filePath + prDescription + diff前300字 */
    private RagContext retrieveRagFileLevel(List<MethodNode> methods, String diffContent,
                                            String language, String filePath, String prDescription) {
        try {
            String query;
            if (methods != null && !methods.isEmpty()) {
                String className     = methods.get(0).getClassName();
                String methodSummary = methods.stream().map(MethodNode::getSignature)
                        .collect(Collectors.joining("\n"));
                query = String.format("Language: %s\nFile: %s\nClass: %s\nMethods:\n%s\nIntent: %s",
                        language, filePath, className, methodSummary, prDescription);
            } else {
                String diffSnippet = diffContent != null
                        ? diffContent.substring(0, Math.min(diffContent.length(), 300)) : "";
                query = String.format("Language: %s\nFile: %s\nIntent: %s\n%s",
                        language, filePath, prDescription, diffSnippet);
            }
            return RagContext.builder().snippets(knowledgeBaseService.searchSnippets(query, 3))
                    .fromCache(false).build();
        } catch (Exception e) {
            log.warn("[ChangeAnalyzer] FILE RAG 失败: {}", e.getMessage());
            return RagContext.builder().snippets(Collections.emptyList()).fromCache(false).build();
        }
    }

    /** DELETE 专用：从 diff 提取被删除词段 */
    private RagContext retrieveRagDiff(String diffContent, String language) {
        try {
            StringBuilder queryWords = new StringBuilder("Language: ").append(language).append("\n");
            if (diffContent != null) {
                Matcher m = DELETED_WORD_PATTERN.matcher(diffContent);
                int count = 0;
                while (m.find() && count < 10) {
                    queryWords.append(m.group(1).trim()).append(" ");
                    count++;
                }
            }
            return RagContext.builder().snippets(knowledgeBaseService.searchSnippets(queryWords.toString().trim(), 3))
                    .fromCache(false).build();
        } catch (Exception e) {
            log.warn("[ChangeAnalyzer] DELETE RAG 失败: {}", e.getMessage());
            return RagContext.builder().snippets(Collections.emptyList()).fromCache(false).build();
        }
    }

    // ---- 风险等级计算 ----

    private RiskLevel calcRiskByLines(int changedLines) {
        if (changedLines < 20)  return RiskLevel.LOW;
        if (changedLines < 100) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }

    private RiskLevel calcRiskByMethodLines(MethodNode method) {
        int lines = method.getEndLine() - method.getStartLine() + 1;
        if (lines < 30) return RiskLevel.LOW;
        if (lines < 80) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }

    // ---- 辅助方法 ----

    private List<MethodNode> parseMethods(String source, String language) {
        if ("Java".equalsIgnoreCase(language)) {
            return astParserService.parseJavaMethods(source);
        }
        return astParserService.parseNonJavaMethods(source, language);
    }

    private int countChangedLines(String diffContent) {
        if (diffContent == null) return 0;
        return (int) diffContent.lines()
                .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
                .count();
    }

    private String extractDiffSnippetForMethod(String diffContent, MethodNode method) {
        if (diffContent == null || method == null) return diffContent;
        String[] lines = diffContent.split("\n", -1);
        StringBuilder snippet = new StringBuilder();
        boolean inRelevantHunk = false;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // 同时解析旧文件行号（-）和新文件行号（+），任一范围覆盖方法即纳入
                // 删除场景：方法行号在旧文件（-侧），新增/修改场景：方法行号在新文件（+侧）
                Matcher newFile = Pattern.compile("\\+(\\d+)(?:,(\\d+))?").matcher(line);
                Matcher oldFile = Pattern.compile("-(\\d+)(?:,(\\d+))?").matcher(line);
                inRelevantHunk = false;
                if (newFile.find()) {
                    int start = Integer.parseInt(newFile.group(1));
                    int len   = newFile.group(2) != null ? Integer.parseInt(newFile.group(2)) : 1;
                    if (method.getStartLine() <= start + len - 1 && method.getEndLine() >= start) {
                        inRelevantHunk = true;
                    }
                }
                if (!inRelevantHunk && oldFile.find()) {
                    int start = Integer.parseInt(oldFile.group(1));
                    int len   = oldFile.group(2) != null ? Integer.parseInt(oldFile.group(2)) : 1;
                    if (method.getStartLine() <= start + len - 1 && method.getEndLine() >= start) {
                        inRelevantHunk = true;
                    }
                }
            }
            if (inRelevantHunk) snippet.append(line).append("\n");
        }
        return snippet.length() > 0 ? snippet.toString() : diffContent;
    }

    private boolean isEntireMethodDeleted(String diffSnippet, MethodNode method) {
        if (diffSnippet == null) return false;
        long deletedLines   = diffSnippet.lines()
                .filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
        long totalMethodLines = method.getEndLine() - method.getStartLine() + 1;
        return totalMethodLines > 0 && deletedLines >= totalMethodLines * 0.8;
    }
}
