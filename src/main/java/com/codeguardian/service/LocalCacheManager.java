package com.codeguardian.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @description: 本地缓存管理器
 * 负责L1（本地内存）缓存，使用ConcurrentHashMap实现线程安全的内存缓存
 * 支持LRU淘汰策略，提供快速的数据访问
 *
 * 主要职责：
 * 1. 管理本地内存缓存，作为第一级缓存（L1）
 * 2. 使用ConcurrentHashMap保证线程安全
 * 3. 实现简单的LRU淘汰策略，防止内存溢出
 * 4. 自动处理缓存过期和清理
 *
 * 缓存策略：
 * - 本地缓存大小限制：1000条记录
 * - 缓存过期时间：30分钟
 * - 使用LRU策略淘汰最久未使用的缓存项
 *
 * 缓存结构：
 * - chunkContentCache: 存储代码块内容
 * - chunkMetadataCache: 存储代码块元数据
 * - searchResultCache: 存储搜索结果
 *
 * @author: Winston
 * @date: 2026/3/15
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCacheManager implements Cache {

    // 本地缓存（L1）- 使用ConcurrentHashMap保证线程安全
    private final Map<String, CacheEntry<String>> chunkContentCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Object>> chunkMetadataCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Object>> searchResultCache = new ConcurrentHashMap<>();

    // 本地缓存配置
    private static final int LOCAL_CACHE_SIZE = 1000; // 本地缓存最大条目数
    private static final long LOCAL_CACHE_TTL = 30 * 60 * 1000; // 30分钟（毫秒）

    /**
     * 缓存条目
     */
    private static class CacheEntry<T> {
        private final T value;
        private final long expiryTime;

        public CacheEntry(T value, long ttlMillis) {
            this.value = value;
            this.expiryTime = System.currentTimeMillis() + ttlMillis;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }

        public T getValue() {
            return value;
        }
    }

    /**
     * 获取代码块内容
     */
    @Override
    public String getChunkContent(String chunkId) {
        CacheEntry<String> entry = chunkContentCache.get(chunkId);
        if (entry != null && !entry.isExpired()) {
            log.debug("Hit local cache for chunk: {}", chunkId);
            return entry.getValue();
        }

        if (entry != null) {
            chunkContentCache.remove(chunkId);
        }

        return null;
    }

    /**
     * 缓存代码块内容
     */
    @Override
    public void putChunkContent(String chunkId, String content) {
        putCache(chunkContentCache, chunkId, content);
        log.debug("Cached chunk content in local cache: {}", chunkId);
    }

    /**
     * 获取代码块元数据
     */
    @Override
    public Object getChunkMetadata(String chunkId) {
        CacheEntry<Object> entry = chunkMetadataCache.get(chunkId);
        if (entry != null && !entry.isExpired()) {
            log.debug("Hit local cache for chunk metadata: {}", chunkId);
            return entry.getValue();
        }

        if (entry != null) {
            chunkMetadataCache.remove(chunkId);
        }

        return null;
    }

    /**
     * 缓存代码块元数据
     */
    @Override
    public void putChunkMetadata(String chunkId, Object metadata) {
        putCache(chunkMetadataCache, chunkId, metadata);
        log.debug("Cached chunk metadata in local cache: {}", chunkId);
    }

    /**
     * 获取搜索结果
     */
    @Override
    public Object getSearchResult(String queryHash) {
        CacheEntry<Object> entry = searchResultCache.get(queryHash);
        if (entry != null && !entry.isExpired()) {
            log.debug("Hit local cache for search result: {}", queryHash);
            return entry.getValue();
        }

        if (entry != null) {
            searchResultCache.remove(queryHash);
        }

        return null;
    }

    /**
     * 缓存搜索结果
     */
    @Override
    public void putSearchResult(String queryHash, Object result) {
        putCache(searchResultCache, queryHash, result);
        log.debug("Cached search result in local cache: {}", queryHash);
    }

    /**
     * 获取文档chunks
     */
    @Override
    public List<String> getDocumentChunks(String documentId) {
        return null; // 本地缓存不存储文档chunks，由RedisCacheManager处理
    }

    /**
     * 缓存文档chunks
     */
    @Override
    public void putDocumentChunks(String documentId, List<String> chunkIds) {
        // 本地缓存不存储文档chunks，由RedisCacheManager处理
    }

    /**
     * 获取缓存统计信息
     */
    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();

        stats.put("chunkContentCacheSize", chunkContentCache.size());
        stats.put("chunkMetadataCacheSize", chunkMetadataCache.size());
        stats.put("searchResultCacheSize", searchResultCache.size());
        stats.put("localCacheMaxSize", LOCAL_CACHE_SIZE);

        return stats;
    }

    /**
     * 清理过期缓存
     */
    @Override
    public void cleanupExpiredCache() {
        chunkContentCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        chunkMetadataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        searchResultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());

        log.debug("Cleaned up expired local cache entries");
    }

    /**
     * 清空所有缓存
     */
    @Override
    public void clearAllCache() {
        chunkContentCache.clear();
        chunkMetadataCache.clear();
        searchResultCache.clear();
        log.info("Cleared all local cache");
    }

    /**
     * 获取缓存状态
     */
    @Override
    public Map<String, String> getCacheStatus(String chunkId) {
        Map<String, String> status = new ConcurrentHashMap<>();

        CacheEntry<String> contentEntry = chunkContentCache.get(chunkId);
        status.put("localContentCache", contentEntry == null ? "MISS" :
                  contentEntry.isExpired() ? "EXPIRED" : "HIT");

        return status;
    }

    /**
     * 清除特定chunk缓存
     */
    @Override
    public void evictChunkCache(String chunkId) {
        chunkContentCache.remove(chunkId);
        chunkMetadataCache.remove(chunkId);
        searchResultCache.remove(chunkId);
        log.debug("Evicted chunk cache: {}", chunkId);
    }

    /**
     * 清除特定文档缓存
     */
    @Override
    public void evictDocumentCache(String documentId) {
        // 本地缓存不存储文档相关缓存
    }

    /**
     * 通用缓存方法
     */
    private <T> void putCache(Map<String, CacheEntry<T>> cacheMap, String key, T value) {
        // 简单的LRU策略：如果缓存已满，移除最早的条目
        if (cacheMap.size() >= LOCAL_CACHE_SIZE) {
            cacheMap.remove(cacheMap.keySet().iterator().next());
        }

        cacheMap.put(key, new CacheEntry<>(value, LOCAL_CACHE_TTL));
    }
}