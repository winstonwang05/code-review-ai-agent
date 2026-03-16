package com.codeguardian.service;

import java.util.List;
import java.util.Map;

/**
 * @description: 统一缓存接口
 * 定义所有缓存操作的标准化接口
 *
 * @author: Winston
 * @date: 2026/3/15
 * @version: 1.0
 */
public interface Cache {

    /**
     * 获取代码块内容
     */
    String getChunkContent(String chunkId);

    /**
     * 缓存代码块内容
     */
    void putChunkContent(String chunkId, String content);

    /**
     * 获取代码块元数据
     */
    Object getChunkMetadata(String chunkId);

    /**
     * 缓存代码块元数据
     */
    void putChunkMetadata(String chunkId, Object metadata);

    /**
     * 获取搜索结果
     */
    Object getSearchResult(String queryHash);

    /**
     * 缓存搜索结果
     */
    void putSearchResult(String queryHash, Object result);

    /**
     * 获取文档chunks
     */
    List<String> getDocumentChunks(String documentId);

    /**
     * 缓存文档chunks
     */
    void putDocumentChunks(String documentId, List<String> chunkIds);

    /**
     * 获取缓存统计信息
     */
    Map<String, Object> getStats();

    /**
     * 清理过期缓存
     */
    void cleanupExpiredCache();

    /**
     * 清空所有缓存
     */
    void clearAllCache();

    /**
     * 获取缓存状态
     */
    Map<String, String> getCacheStatus(String chunkId);

    /**
     * 清除特定chunk缓存
     */
    void evictChunkCache(String chunkId);

    /**
     * 清除特定文档缓存
     */
    void evictDocumentCache(String documentId);
}