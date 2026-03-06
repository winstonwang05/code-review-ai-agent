package com.codeguardian.service.ai.config;

import com.codeguardian.config.AIConfigProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * @description:  AI模型配置管理器
 * <p>负责管理多个AI模型提供商的配置，支持从配置文件或环境变量加载</p>
 * @author: Winston
 * @date: 2026/3/3 15:40
 * @version: 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AIModelConfigManager {

    private final AIConfigProperties aiConfigProperties;
    private final Map<String, ModelProviderConfig> configCache = new HashMap<>();





    /**
     * 构建模型提供商配置
     */
    private ModelProviderConfig buildConfig(String providerName) {
        String upperProviderName = providerName.toUpperCase();
        // 1.先从配置属性中根据ai服务提供商获取对应的配置
        AIConfigProperties.ProviderConfig providerConfig = aiConfigProperties
                .getProviderConfig(upperProviderName);
        // 2.如果获取的配置属性不为空，构建

        ModelProviderConfig.ModelProviderConfigBuilder builder = ModelProviderConfig.builder()
                .providerName(upperProviderName);

        boolean globallyEnabled = aiConfigProperties.getEnabled() == null || aiConfigProperties.getEnabled();

        if (providerConfig != null) {
            // 设置属性
            builder.baseUrl(providerConfig.getBaseUrl())
                    .apiKey(providerConfig.getApiKey())
                    .defaultModel(providerConfig.getModel())
                    .enabled(globallyEnabled &&
                            providerConfig.getEnabled() != null ?
                            providerConfig.getEnabled() : true)
                    .connectTimeout(providerConfig.getConnectTimeout() != null
                    ? providerConfig.getConnectTimeout()
                    : aiConfigProperties.getTimeout())
                    .readTimeout(providerConfig.getReadTimeout() != null
                            ? providerConfig.getReadTimeout()
                            : aiConfigProperties.getTimeout())
                    .writeTimeout(providerConfig.getWriteTimeout() != null
                            ? providerConfig.getWriteTimeout()
                            : aiConfigProperties.getTimeout())
                    .maxRetries(providerConfig.getMaxRetries() != null
                            ? providerConfig.getMaxRetries()
                            : aiConfigProperties.getMaxRetries());
        }  else {
            // 如果没有配置，使用默认值并记录警告
            log.warn("提供商 {} 未在配置文件中配置，使用默认值", upperProviderName);
            builder.baseUrl("")
                    .apiKey("")
                    .enabled(false)
                    .connectTimeout(aiConfigProperties.getTimeout())
                    .readTimeout(aiConfigProperties.getTimeout())
                    .writeTimeout(aiConfigProperties.getTimeout())
                    .maxRetries(aiConfigProperties.getMaxRetries());
        }

        // 根据不同的提供商设置默认模型（如果未配置）
        String defaultModel = providerConfig != null ? providerConfig.getModel() : null;
        if (!StringUtils.hasText(defaultModel)) {
            switch (upperProviderName) {
                case "OPENAI" :
                    defaultModel = "gpt-3.5-turbo";
                    break;
                case "QWEN" :
                    defaultModel = "qwen3-max";
                    break;
                case "DEEPSEEK" :
                    defaultModel = "deepseek-r1:8b";
                    break;
                default:
                    defaultModel = "gpt-3.5-turbo";
            }
            builder.defaultModel(defaultModel);
        }
        ModelProviderConfig config = builder.build();
        log.info("构建模型配置: provider={}, baseUrl={}, model={}, enabled={}",
                config.getProviderName(),
                config.getBaseUrl(),
                config.getDefaultModel(),
                config.getEnabled());

        return config;


    }

    /**
     * 获取所有已配置的提供商列表
     */
    public java.util.List<String> getConfiguredProviders() {
        return aiConfigProperties.getProviders().entrySet().stream()
                .filter(entry -> {
                    if (entry.getValue() == null
                            || entry.getValue().getEnabled() == null
                            || !entry.getValue().getEnabled()
                            || !StringUtils.hasText(entry.getValue().getBaseUrl())) {
                        return false;
                    }

                    // 对于本地DeepSeek（Ollama），apiKey可以为空
                    String providerName = entry.getKey().toUpperCase();
                    if ("DEEPSEEK".equals(providerName)) {
                        String baseUrl = entry.getValue().getBaseUrl();
                        boolean isLocalOllama = baseUrl != null
                                && (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1"));
                        if (isLocalOllama) {
                            // 本地服务不需要apiKey
                            return true;
                        }
                    }

                    // 其他提供商需要apiKey
                    return StringUtils.hasText(entry.getValue().getApiKey());
                })
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }




    /**
     * 清除配置缓存
     */
    public void clearCache() {
        configCache.clear();
        log.info("已清除AI模型配置缓存");
    }



}
