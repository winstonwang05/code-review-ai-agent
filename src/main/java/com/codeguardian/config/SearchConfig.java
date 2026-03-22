package com.codeguardian.config;

/**
 * 搜索配置类
 * 统一管理搜索相关的参数配置
 * @author Winston
 */
public class SearchConfig {

    // BM25 算法参数
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final int MAX_TERMS_PER_DOC = 1000;

    // 搜索参数
    private static final int DEFAULT_TOP_K = 10;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75;
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_MIN_CHUNK_SIZE = 350;
    private static final int MAX_CHUNK_CACHE_SIZE = 5000;
    private static final long QUERY_CACHE_EXPIRE_HOURS = 1;

    // 文档处理参数
    private static final String DEFAULT_CATEGORY = "CODE_STYLE";
    private static final String CHUNK_SEPARATOR = "_chunk_";
    private static final String SOURCE_DOC_ID = "source_doc_id";

    // RRF 参数
    private static final int RRF_K = 60;

    // 获取方法
    public static double getBm25K1() {
        return BM25_K1;
    }

    public static double getBm25B() {
        return BM25_B;
    }

    public static int getMaxTermsPerDoc() {
        return MAX_TERMS_PER_DOC;
    }

    public static int getDefaultTopK() {
        return DEFAULT_TOP_K;
    }

    public static double getDefaultSimilarityThreshold() {
        return DEFAULT_SIMILARITY_THRESHOLD;
    }

    public static int getDefaultChunkSize() {
        return DEFAULT_CHUNK_SIZE;
    }

    public static int getDefaultMinChunkSize() {
        return DEFAULT_MIN_CHUNK_SIZE;
    }

    public static int getMaxChunkCacheSize() {
        return MAX_CHUNK_CACHE_SIZE;
    }

    public static long getQueryCacheExpireHours() {
        return QUERY_CACHE_EXPIRE_HOURS;
    }

    public static String getDefaultCategory() {
        return DEFAULT_CATEGORY;
    }

    public static String getChunkSeparator() {
        return CHUNK_SEPARATOR;
    }

    public static String getSourceDocId() {
        return SOURCE_DOC_ID;
    }

    public static int getRrfK() {
        return RRF_K;
    }
}