package com.codeguardian.service.diff.prompt;

import com.codeguardian.service.diff.model.ChangeUnit;
import com.codeguardian.service.diff.model.MethodNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 删除场景专用 Prompt 策略（FULL_DELETE / PARTIAL_DELETE）
 *
 * 与 WebhookPromptStrategy 的核心区别：
 * - 审查目标不是"新代码有没有问题"，而是"删除这段代码是否安全"
 * - 重点：调用方是否已清理？是否引入悬挂引用？接口实现是否同步移除？
 */
@Component("deletePromptStrategy")
public class DeletePromptStrategy implements DiffAwarePromptStrategy {

    @Override
    public String buildPrompt(ChangeUnit unit, String commitMessage) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定（删除场景专用）
        sb.append("""
                你是一位拥有 20 年经验的资深架构师和顶级 Code Reviewer。
                审查红线：
                1. 忽略代码风格等格式问题，只关注删除操作引入的风险。
                2. 不要猜测未提供的上下文。如果没有发现实质性问题，直接返回空数组 []。
                3. 所有发现必须基于提供的 Diff 变更内容。
                4. 所有字段内容必须使用中文。

                ## 删除场景审查目标
                此次变更包含代码删除，请重点审查：
                1. 被删除的方法/类是否仍有调用方？删除后是否引入编译错误或运行时异常？
                2. 是否存在悬挂引用或未释放的资源（连接、锁、文件句柄）？
                3. 若删除的是接口实现，其他实现类或调用方是否已同步更新？
                4. 删除是否破坏了既有的业务契约或 API 兼容性？

                """);

        // 2. 变更上下文
        sb.append("## 本次变更信息\n");
        sb.append("- **文件路径**: `").append(unit.getFilePath()).append("`\n");
        sb.append("- **变更类型**: ").append(unit.getChangeType() == com.codeguardian.service.diff.model.ChangeType.FULL_DELETE
                ? "整体删除（方法/文件删除）" : "局部删除（方法内删行）").append("\n");
        sb.append("- **语言**: ").append(unit.getLanguage()).append("\n");
        if (commitMessage != null && !commitMessage.isBlank()) {
            sb.append("- **Commit Message**: ").append(commitMessage).append("\n");
        }
        sb.append("\n");

        // 3. 开发意图（PR 描述不空时注入）
        String desc = unit.getPrDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append("## 开发意图（PR 描述）\n");
            sb.append(desc).append("\n\n");
            sb.append("> 请审查：删除操作是否在 PR 描述意图范围内？是否有遗漏的清理工作？\n\n");
        }

        // 4. AST 信息（方法级时显示）
        MethodNode method = unit.getMethod();
        if (method != null) {
            sb.append("## 被删除的方法\n");
            sb.append("- **类名**: ").append(method.getClassName()).append("\n");
            sb.append("- **方法签名**: `").append(method.getSignature()).append("`\n");
            sb.append("- **行号范围**: ").append(method.getStartLine())
              .append(" - ").append(method.getEndLine()).append("\n");
            if (!method.getAnnotations().isEmpty()) {
                sb.append("- **注解**: ").append(String.join(", ", method.getAnnotations())).append("\n");
            }
            sb.append("\n");
        }

        // 5. Diff 片段（被删除的代码）
        if (unit.getDiffSnippet() != null && !unit.getDiffSnippet().isBlank()) {
            sb.append("## 被删除的代码（- 行为删除内容）\n");
            sb.append("```diff\n").append(unit.getDiffSnippet()).append("\n```\n\n");
        }

        // 6. RAG 知识库上下文
        List<String> snippets = unit.getRagContext() != null ? unit.getRagContext().getSnippets() : List.of();
        if (!snippets.isEmpty()) {
            sb.append("## 相关代码规范与最佳实践（RAG 检索）\n");
            for (int i = 0; i < snippets.size(); i++) {
                sb.append(i + 1).append(". ").append(snippets.get(i)).append("\n\n");
            }
        }

        // 7. 输出格式要求
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
}
