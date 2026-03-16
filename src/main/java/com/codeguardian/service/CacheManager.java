package com.codeguardian.service;

import com.codeguardian.config.CacheConfig;
import com.codeguardian.service.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @description: 缓存管理器
 * 作为多级缓存的协调者，统一管理所有缓存操作
 *
 * 主要职责：
 * 1. 协调LocalCacheManager和RedisCacheManager，提供统一的缓存接口
 * 2. 提供缓存统计功能，包括命中率、缓存大小等指标
 * 3. 实现缓存预热策略，提高系统启动性能
 * 4. 提供缓存清理和失效功能
 * 5. 监控缓存使用情况，帮助优化缓存策略
 *
 * 架构设计：
 * - LocalCacheManager：第一级缓存（L1），内存缓存，快速访问
 * - RedisCacheManager：第二级缓存（L2），持久化缓存，数据持久性
 * - CacheManager：协调层，提供统一接口和高级功能
 *
 * @author: Winston
 * @date: 2026/3/15
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheManager implements Cache {

    private final LocalCacheManager localCacheManager;
    private final RedisCacheManager redisCacheManager;
    private final CacheConfig cacheConfig;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 获取代码块内容
     */
    @Override
    public String getChunkContent(String chunkId) {
        return localCacheManager.getChunkContent(chunkId);
    }

    /**
     * 缓存代码块内容
     */
    @Override
    public void putChunkContent(String chunkId, String content) {
        localCacheManager.putChunkContent(chunkId, content);
    }

    /**
     * 获取代码块元数据
     */
    @Override
    public Object getChunkMetadata(String chunkId) {
        return localCacheManager.getChunkMetadata(chunkId);
    }

    /**
     * 缓存代码块元数据
     */
    @Override
    public void putChunkMetadata(String chunkId, Object metadata) {
        localCacheManager.putChunkMetadata(chunkId, metadata);
    }

    /**
     * 获取搜索结果
     */
    @Override
    public Object getSearchResult(String queryHash) {
        return localCacheManager.getSearchResult(queryHash);
    }

    /**
     * 缓存搜索结果
     */
    @Override
    public void putSearchResult(String queryHash, Object result) {
        localCacheManager.putSearchResult(queryHash, result);
    }

    /**
     * 获取文档chunks
     */
    @Override
    public List<String> getDocumentChunks(String documentId) {
        return localCacheManager.getDocumentChunks(documentId);
    }

    /**
     * 缓存文档chunks
     */
    @Override
    public void putDocumentChunks(String documentId, List<String> chunkIds) {
        localCacheManager.putDocumentChunks(documentId, chunkIds);
    }

    /**
     * 获取缓存统计信息
     */
    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();

        // 本地缓存统计
        stats.put("localCache", localCacheManager.getStats());

        // Redis缓存统计
        try {
            Map<String, Object> redisStats = new java.util.HashMap<>();
            redisStats.put("chunkContent", redisCacheManager.getChunkContentStats());
            redisStats.put("searchResult", redisCacheManager.getSearchResultStats());
            stats.put("redisCache", redisStats);
        } catch (Exception e) {
            log.warn("Failed to get Redis cache stats: {}", e.getMessage());
        }

        return stats;
    }

    /**
     * 清理过期缓存
     */
    @Override
    public void cleanupExpiredCache() {
        localCacheManager.cleanupExpiredCache();
        log.debug("Cleaned up expired cache entries");
    }

    /**
     * 清空所有缓存
     */
    @Override
    public void clearAllCache() {
        localCacheManager.clearAllCache();
        redisCacheManager.clearAllCache();
        log.info("Cleared all cache");
    }

    /**
     * 获取缓存状态
     */
    @Override
    public Map<String, String> getCacheStatus(String chunkId) {
        return localCacheManager.getCacheStatus(chunkId);
    }

    /**
     * 清除特定chunk缓存
     */
    @Override
    public void evictChunkCache(String chunkId) {
        localCacheManager.evictChunkCache(chunkId);
        redisCacheManager.evictChunkCache(chunkId);
        log.info("Evicted chunk cache: {}", chunkId);
    }

    /**
     * 清除特定文档缓存
     */
    @Override
    public void evictDocumentCache(String documentId) {
        localCacheManager.evictDocumentCache(documentId);
        redisCacheManager.evictDocumentCache(documentId);
        log.info("Evicted document cache: {}", documentId);
    }

    /**
     * 预热缓存
     */
    @Scheduled(initialDelay = 30000, fixedRate = 3600000) // 30秒后开始，每小时执行一次
    public void warmupCache() {
        if (!cacheConfig.getWarmup().isEnabled()) {
            return;
        }

        log.info("Starting cache warmup...");

        try {
            // 预热高频访问的文档
            if (knowledgeBaseService != null) {
                List<String> documentIdsToWarm = cacheConfig.getWarmup().getDocumentIds();
                if (documentIdsToWarm.isEmpty()) {
                    documentIdsToWarm = List.of("doc1", "doc2"); // 示例ID
                }

                for (String docId : documentIdsToWarm) {
                    warmupDocument(docId);
                }
            }

            // 预热热门查询
            List<String> sampleQueries = cacheConfig.getWarmup().getSampleQueries();
            for (String query : sampleQueries) {
                warmupQuery(query);
            }

            log.info("Cache warmup completed");
        } catch (Exception e) {
            log.error("Cache warmup failed: {}", e.getMessage());
        }
    }

    /**
     * 预热特定文档
     */
    private void warmupDocument(String documentId) {
        try {
            List<String> chunkIds = getDocumentChunks(documentId);
            if (chunkIds == null || chunkIds.isEmpty()) {
                return;
            }

            for (String chunkId : chunkIds) {
                getChunkContent(chunkId);
            }

            log.debug("Warmed up document: {}", documentId);
        } catch (Exception e) {
            log.warn("Failed to warm up document: {}, error: {}", documentId, e.getMessage());
        }
    }

    /**
     * 预热特定查询
     */
    private void warmupQuery(String query) {
        try {
            String queryHash = Integer.toHexString(query.hashCode());
            getSearchResult(queryHash);
            log.debug("Warmed up query: {}", query);
        } catch (Exception e) {
            log.warn("Failed to warm up query: {}, error: {}", query, e.getMessage());
        }
    }

    /**
     * 异步预热缓存
     */
    public CompletableFuture<Void> asyncWarmupCache() {
        return CompletableFuture.runAsync(this::warmupCache);
    }
}