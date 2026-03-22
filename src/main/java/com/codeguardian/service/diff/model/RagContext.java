package com.codeguardian.service.diff.model;

import com.codeguardian.entity.Finding;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagContext {
    /** searchSnippets 返回的知识库片段 */
    private List<String> snippets;
    /** true=命中语义指纹缓存，直接复用旧审查结果，跳过 AI 调用 */
    private boolean fromCache;
    /** 命中缓存时直接返回的 Finding 列表 */
    private List<Finding> cachedFindings;
}
