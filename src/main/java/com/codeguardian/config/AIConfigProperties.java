package com.codeguardian.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * @description: AI配置属性
 * 支持多个AI模型提供商的独立配置
 * @author: Winston
 * @date: 2026/3/3 12:02
 * @version: 1.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIConfigProperties {

    /**
     * 是否开启，默认开启
     */
    private Boolean enabled = true;

    /**
     * 默认的AI提供商：OPENAI， QWEN， DEEPSEEK
     */
    private String provider = "OPENAI";

    /**
     * 默认的超时时间（秒）
     */
    private Integer timeout = 60;

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;


    /**
     * 各模型提供商的独立配置
     * key: 提供商名称（OPENAI, QWEN, DEEPSEEK）
     * value: 该提供商的配置
     */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 单个模型提供商配置
     */
    @Data
    public static class ProviderConfig {
        /**
         * API 的基础URL
         */
        private String baseUrl;

        /**
         * API密钥
         */
        private String apiKey;

        /**
         * 默认的模型名称
         */
        private String model;

        /**
         * 是否启用
         */
        private Boolean enabled = true;

        /**
         * 连接超时时间（秒），如果未设置则使用全局timeout
         */
        private Integer connectTimeout;

        /**
         * 读取超时时间（秒），如果未设置则使用全局timeout
         */
        private Integer readTimeout;

        /**
         * 写入超时时间（秒），如果未设置则使用全局timeout
         */
        private Integer writeTimeout;

        /**
         * 最大重试次数，如果未设置则使用全局maxRetries
         */
        private Integer maxRetries;
    }


    /**
     * 获取指定提供商的配置
     */
    public ProviderConfig getProviderConfig(String providerName) {
        if (providerName == null || providerName.trim().isEmpty()) {
            providerName = provider;
        }
        return providers.get(providerName.toUpperCase());
    }

    /**
     * 检查指定提供商是否已配置
     */
    public boolean isProviderConfigured(String providerName) {
        ProviderConfig config = getProviderConfig(providerName);
        return config != null
                && config.getEnabled() != null && config.getEnabled()
                && config.getBaseUrl() != null && !config.getBaseUrl().trim().isEmpty()
                && config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
    }


}
