package com.codeguardian.service.diff.prompt;

import com.codeguardian.service.diff.model.ChangeUnit;
import com.codeguardian.service.diff.model.MethodNode;
import com.codeguardian.service.diff.model.ReviewScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CI/CD 专用 Prompt 策略
 * 输出要求：严格 JSON 数组，不含任何 Markdown 装饰，供流水线机器解析
 *
 * 与 WebhookPromptStrategy 的区别：
 * - 强调"只输出 JSON 数组"
 * - 紧凑 KV 格式，无 Markdown 装饰
 * - RISK_LEVEL / PR_INTENT 字段注入审查松紧度与开发意图
 */
@Component("cicdPromptStrategy")
public class CicdPromptStrategy implements DiffAwarePromptStrategy {

    @Override
    public String buildPrompt(ChangeUnit unit, String commitMessage) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定（CI/CD 强调机器可读）
        sb.append("""
                你是一位资深代码审查专家，正在为 CI/CD 流水线提供自动化代码审查服务。
                严格要求：
                1. 只输出 JSON 数组，不输出任何 Markdown、注释或说明文字。
                2. 忽略代码风格、空格缩进等格式问题，只关注逻辑缺陷、安全漏洞、性能瓶颈和并发安全。
                3. 不要猜测未提供的上下文。如果没有发现实质性问题，输出 []。
                4. 所有发现必须基于提供的 Diff 变更内容。
                5. 所有字段内容使用中文，filePath 字段使用原始文件路径。

                """);

        // 2. 变更上下文（紧凑 KV 格式）
        sb.append("FILE: ").append(unit.getFilePath()).append("\n");
        sb.append("CHANGE_TYPE: ").append(unit.getChangeType().name()).append("\n");
        sb.append("LANGUAGE: ").append(unit.getLanguage()).append("\n");
        sb.append("REVIEW_SCOPE: ").append(unit.getReviewScope() != null ? unit.getReviewScope().name() : "METHOD_LEVEL").append("\n");

        // 审查强度
        if (unit.getRiskLevel() != null) {
            int methodCount = unit.getAllChangedMethods() != null ? unit.getAllChangedMethods().size()
                    : (unit.getMethod() != null ? 1 : 0);
            sb.append("RISK_LEVEL: ").append(unit.getRiskLevel().name()).append("\n");
            sb.append("CHANGED_UNITS: ").append(methodCount).append("\n");
        }

        // PR 意图
        String prDesc = unit.getPrDescription();
        if (prDesc != null && !prDesc.isBlank()) {
            sb.append("PR_INTENT: ").append(prDesc.replace("\n", " ")).append("\n");
        }
        if (commitMessage != null && !commitMessage.isBlank()) {
            sb.append("COMMIT_MSG: ").append(commitMessage.replace("\n", " ")).append("\n");
        }

        // AST 信息
        if (ReviewScope.FILE_LEVEL.equals(unit.getReviewScope())) {
            List<MethodNode> methods = unit.getAllChangedMethods();
            if (methods != null && !methods.isEmpty()) {
                sb.append("CHANGED_METHODS:\n");
                for (MethodNode m : methods) {
                    sb.append("  - ").append(m.getClassName()).append(".").append(m.getSignature())
                      .append(" [").append(m.getStartLine()).append("-").append(m.getEndLine()).append("]\n");
                }
            }
        } else {
            MethodNode method = unit.getMethod();
            if (method != null) {
                sb.append("METHOD: ").append(method.getSignature()).append("\n");
                sb.append("CLASS: ").append(method.getClassName()).append("\n");
                sb.append("LINES: ").append(method.getStartLine()).append("-").append(method.getEndLine()).append("\n");
            }
        }
        sb.append("\n");

        // 3. Diff 片段
        if (unit.getDiffSnippet() != null && !unit.getDiffSnippet().isBlank()) {
            sb.append("DIFF:\n").append(unit.getDiffSnippet()).append("\n\n");
        }

        // 4. 完整代码
        if (unit.getCodeToReview() != null && !unit.getCodeToReview().isBlank()) {
            sb.append("CODE:\n").append(addLineNumbers(unit.getCodeToReview())).append("\n\n");
        }

        // 5. RAG 上下文（紧凑格式）
        List<String> snippets = unit.getRagContext() != null ? unit.getRagContext().getSnippets() : List.of();
        if (!snippets.isEmpty()) {
            sb.append("STANDARDS:\n");
            for (String snippet : snippets) {
                sb.append("- ").append(snippet.replace("\n", " ")).append("\n");
            }
            sb.append("\n");
        }

        // 6. 严格 JSON 输出格式要求
        sb.append("""
                OUTPUT_FORMAT (JSON array only, no other text):
                [
                  {
                    "severity": "CRITICAL|HIGH|MEDIUM|LOW",
                    "title": "问题标题（中文）",
                    "location": "文件名:行号",
                    "filePath": "完整文件路径",
                    "startLine": 行号整数,
                    "endLine": 行号整数,
                    "description": "问题描述（中文）",
                    "suggestion": "建议：修复建议（中文）",
                    "diff": "- 旧代码\\n+ 新代码",
                    "category": "SECURITY|PERFORMANCE|BUG|CODE_STYLE|MAINTAINABILITY"
                  }
                ]
                """);

        return sb.toString();
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
