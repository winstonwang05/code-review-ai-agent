package com.codeguardian.service.diff.prompt;

import com.codeguardian.service.diff.model.ChangeUnit;
import com.codeguardian.service.diff.model.MethodNode;
import com.codeguardian.service.diff.model.ReviewScope;
import com.codeguardian.service.diff.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 专用 Prompt 策略
 * 输出要求：JSON 数组（Finding 结构），供 CodeReviewOutputParser 解析后写入 MR 评论
 *
 * 动态 section：
 * - 审查强度：由 riskLevel + 变更规模动态注入，驱动 AI 松紧度
 * - 开发意图：由 prDescription 注入，引导 AI 校验实现与意图一致性
 * - AST 信息：METHOD_LEVEL 时为单方法；FILE_LEVEL 时为所有变更方法概览
 */
@Component("webhookPromptStrategy")
public class WebhookPromptStrategy implements DiffAwarePromptStrategy {

    @Override
    public String buildPrompt(ChangeUnit unit, String commitMessage) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定 + 防幻觉 Guardrails
        sb.append("""
                你是一位拥有 20 年经验的资深架构师和顶级 Code Reviewer。
                审查红线：
                1. 忽略代码风格、空格缩进、换行等格式问题，只关注逻辑缺陷、安全漏洞、性能瓶颈和并发安全。
                2. 不要猜测未提供的上下文。如果没有发现实质性问题，直接返回空数组 []。
                3. 所有发现必须基于提供的 Diff 变更内容，不能脱离本次修改范围。
                4. 所有字段内容必须使用中文。

                """);

        // 2. 审查强度（动态注入，驱动 AI 松紧度）
        appendRiskSection(sb, unit);

        // 3. 变更上下文
        sb.append("## 本次变更信息\n");
        sb.append("- **文件路径**: `").append(unit.getFilePath()).append("`\n");
        sb.append("- **变更类型**: ").append(describeChangeType(unit)).append("\n");
        sb.append("- **语言**: ").append(unit.getLanguage()).append("\n");
        if (commitMessage != null && !commitMessage.isBlank()) {
            sb.append("- **Commit Message**: ").append(commitMessage).append("\n");
        }
        sb.append("\n");

        // 4. 开发意图（PR 描述不空时注入）
        appendPrDescriptionSection(sb, unit);

        // 5. AST 信息（METHOD_LEVEL 单方法 / FILE_LEVEL 方法概览）
        appendAstSection(sb, unit);

        // 6. Diff 片段
        if (unit.getDiffSnippet() != null && !unit.getDiffSnippet().isBlank()) {
            sb.append("## 变更 Diff（+ 新增 / - 删除）\n");
            sb.append("```diff\n").append(unit.getDiffSnippet()).append("\n```\n\n");
        }

        // 7. 完整代码（供 AI 理解上下文）
        if (unit.getCodeToReview() != null && !unit.getCodeToReview().isBlank()) {
            sb.append("## 完整代码（含行号）\n");
            sb.append("```").append(unit.getLanguage().toLowerCase()).append("\n");
            sb.append(addLineNumbers(unit.getCodeToReview()));
            sb.append("\n```\n\n");
        }

        // 8. RAG 知识库上下文
        List<String> snippets = unit.getRagContext() != null ? unit.getRagContext().getSnippets() : List.of();
        if (!snippets.isEmpty()) {
            sb.append("## 相关代码规范与最佳实践（RAG 检索）\n");
            for (int i = 0; i < snippets.size(); i++) {
                sb.append(i + 1).append(". ").append(snippets.get(i)).append("\n\n");
            }
        }

        // 9. 输出格式要求
        sb.append("""
                ## 输出要求
                请以 JSON 数组格式返回审查结果，每个问题包含以下字段：
                - severity: 严重程度（CRITICAL, HIGH, MEDIUM, LOW）
                - title: 问题标题（中文）
                - location: 问题位置，格式为"文件名:行号"
                - startLine: 起始行号（整数）
                - endLine: 结束行号（整数）
                - description: 问题描述（中文，简洁直接）
                - suggestion: 修复建议（中文，格式为"建议：具体内容"）
                - diff: 修复代码差异（标准 diff 格式，每行以"- "或"+ "开头）
                - category: 问题类别（SECURITY, PERFORMANCE, BUG, CODE_STYLE, MAINTAINABILITY）

                如无问题，返回空数组 []。请直接返回 JSON，不要包含其他文字。
                """);

        return sb.toString();
    }

    private void appendRiskSection(StringBuilder sb, ChangeUnit unit) {
        if (unit.getRiskLevel() == null) return;

        int methodCount = unit.getAllChangedMethods() != null ? unit.getAllChangedMethods().size() : (unit.getMethod() != null ? 1 : 0);
        String scale = unit.getRiskLevel() == RiskLevel.LOW ? "小型" : unit.getRiskLevel() == RiskLevel.MEDIUM ? "中型" : "大型";
        String strategy = switch (unit.getRiskLevel()) {
            case LOW    -> "聚焦 CRITICAL/HIGH 级别问题，避免过度审查，小改动不必面面俱到";
            case MEDIUM -> "全面审查，包括潜在性能和并发问题";
            case HIGH   -> "重点检查接口契约完整性、跨方法副作用、并发安全与资源泄漏";
        };

        sb.append("## 审查强度\n");
        sb.append("- **变更规模**: ").append(scale)
          .append("（涉及方法数=").append(methodCount).append("）\n");
        sb.append("- **审查策略**: ").append(strategy).append("\n\n");
    }

    private void appendPrDescriptionSection(StringBuilder sb, ChangeUnit unit) {
        String desc = unit.getPrDescription();
        if (desc == null || desc.isBlank()) return;

        sb.append("## 开发意图（PR 描述）\n");
        sb.append(desc).append("\n\n");
        sb.append("> 请审查：代码实现是否与上述意图一致？是否存在意图之外的副作用或遗漏？\n\n");
    }

    private void appendAstSection(StringBuilder sb, ChangeUnit unit) {
        if (ReviewScope.FILE_LEVEL.equals(unit.getReviewScope())) {
            // FILE_LEVEL：输出所有变更方法概览
            List<MethodNode> methods = unit.getAllChangedMethods();
            if (methods != null && !methods.isEmpty()) {
                sb.append("## 本次变更涉及的方法（").append(methods.size()).append(" 个）\n");
                for (MethodNode m : methods) {
                    sb.append("- `").append(m.getClassName()).append(".").append(m.getMethodName())
                      .append("(...)` [行 ").append(m.getStartLine()).append("-").append(m.getEndLine()).append("]\n");
                }
                sb.append("\n请逐一审查上述方法的变更，重点关注方法间的交互副作用。\n\n");
            }
        } else {
            // METHOD_LEVEL：单方法详情
            MethodNode method = unit.getMethod();
            if (method != null) {
                sb.append("## AST 解析信息\n");
                sb.append("- **类名**: ").append(method.getClassName()).append("\n");
                sb.append("- **方法签名**: `").append(method.getSignature()).append("`\n");
                sb.append("- **行号范围**: ").append(method.getStartLine())
                  .append(" - ").append(method.getEndLine()).append("\n");
                if (!method.getAnnotations().isEmpty()) {
                    sb.append("- **注解**: ").append(String.join(", ", method.getAnnotations())).append("\n");
                }
                sb.append("\n");
            }
        }
    }

    private String describeChangeType(ChangeUnit unit) {
        return switch (unit.getChangeType()) {
            case ADD            -> "新增";
            case MODIFY         -> "修改";
            case PARTIAL_DELETE -> "局部删除（方法内删行）";
            case FULL_DELETE    -> "整体删除（方法/文件删除）";
        };
    }

    private String addLineNumbers(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
