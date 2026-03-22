package com.codeguardian.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @description: ChatClient工厂类
 * <p>根据提供商名称创建对应的ChatClient，使用Spring AI的ChatClient构建器模式</p>
 * <p>支持 Fallback 降级：主力提供商不可用时按优先级顺序尝试备用提供商</p>
 * @author: Winston
 * @date: 2026/3/5 10:26
 * @version: 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    private final Map<String, ChatModel> chatModelMap;

    /** 主力提供商，配置项 ai.provider=QWEN */
    @Value("${ai.provider:QWEN}")
    private String defaultProvider;

    /** Fallback 优先级顺序，主力提供商 Spring Retry 耗尽后按此顺序尝试 */
    private static final List<String> FALLBACK_ORDER = List.of("QWEN", "DEEPSEEK", "OPENAI");

    /**
     * 根据提供商名称创建 ChatClient
     *
     * @param providerName 提供商名称：OPENAI, QWEN, DEEPSEEK；null 时使用配置的默认提供商
     * @return ChatClient实例
     */
    public ChatClient createChatClient(String providerName) {
        if (providerName == null || providerName.trim().isEmpty()) {
            providerName = defaultProvider;
        }
        String normalizedProvider = providerName.toUpperCase();
        ChatModel chatModel = chatModelMap.get(normalizedProvider);
        if (chatModel == null) {
            log.warn("[ChatClientFactory] 未找到提供商 {} 的 ChatModel，使用默认", normalizedProvider);
            chatModel = chatModelMap.values().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("没有可用的 ChatModel"));
        }
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Spring Retry 耗尽后调用：按 FALLBACK_ORDER 逐个尝试备用提供商
     * 跳过已失败的 failedProvider，返回第一个可用的 ChatClient
     *
     * @param failedProvider 刚刚失败的提供商（跳过它）
     * @return 可用的备用 ChatClient
     * @throws IllegalStateException 所有提供商均不可用
     */
    public ChatClient createFallbackChatClient(String failedProvider) {
        String failed = failedProvider != null ? failedProvider.toUpperCase() : "";

        // 先按 FALLBACK_ORDER 中定义的顺序尝试
        for (String candidate : FALLBACK_ORDER) {
            if (candidate.equals(failed)) continue;
            ChatModel model = chatModelMap.get(candidate);
            if (model != null) {
                log.warn("[ChatClientFactory] 提供商 {} 不可用，降级到 {}", failed, candidate);
                return ChatClient.builder(model).build();
            }
        }

        // FALLBACK_ORDER 之外还有其他注册的提供商（如 LOCAL），也尝试一遍
        // 注意：必须同时排除 failedProvider 和所有已在 FALLBACK_ORDER 中尝试过的提供商，避免重复降级
        for (Map.Entry<String, ChatModel> entry : chatModelMap.entrySet()) {
            String key = entry.getKey();
            if (!key.equals(failed) && !FALLBACK_ORDER.contains(key)) {
                log.warn("[ChatClientFactory] 降级到非标准提供商: {}", key);
                return ChatClient.builder(entry.getValue()).build();
            }
        }

        throw new IllegalStateException("所有 AI 提供商均不可用，failedProvider=" + failedProvider);
    }

    /**
     * 获取可用的模型提供商列表
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(chatModelMap.keySet());
    }
}
