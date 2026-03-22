package com.codeguardian.service.diff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 最小审查单元，对应一次 AI 调用
 * 粒度：方法级（reviewScope=METHOD_LEVEL）或整体级（reviewScope=FILE_LEVEL）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeUnit {
    private String filePath;
    private ChangeType changeType;
    /** 变更方法节点，FILE_LEVEL 时为 null */
    private MethodNode method;
    /** 送给 AI 的代码文本（新增/修改时为方法体，删除/整体时为 diff 片段） */
    private String codeToReview;
    /** 对应的 unified diff 片段，注入 Prompt 提供变更上下文 */
    private String diffSnippet;
    private String language;
    /** RAG 检索结果（含是否命中缓存） */
    private RagContext ragContext;
    /** 语义指纹 key（METHOD_LEVEL ADD/MODIFY 时有值，用于写缓存） */
    private String semanticKey;

    /** 审查粒度：METHOD_LEVEL（方法级）/ FILE_LEVEL（整体级，爆炸式变更/无方法） */
    private ReviewScope reviewScope;
    /** 风险等级，驱动 Prompt 审查松紧度 */
    private RiskLevel riskLevel;
    /** PR 描述 / commit message，透传给 Prompt 提供开发意图上下文 */
    private String prDescription;
    /** FILE_LEVEL 时本次变更涉及的所有方法，用于 Prompt 概览 section */
    private List<MethodNode> allChangedMethods;
}
