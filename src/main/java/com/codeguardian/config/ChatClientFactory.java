package com.codeguardian.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @description: ChatClient工厂类,就是
 * <p>根据提供商名称创建对应的ChatClient，使用Spring AI的ChatClient构建器模式</p>
 * @author: Winston
 * @date: 2026/3/5 10:26
 * @version: 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    private final Map<String, ChatModel> chatModelMap;


    /**
     * 根据提供商名称创建ChatClient
     *
     * @param providerName 提供商名称：OPENAI, QWEN, DEEPSEEK
     * @return ChatClient实例
     */
    public ChatClient createChatClient(String providerName) {
        // 1.检验参数是否为空，如果为空默认使用QWEN
        if (providerName == null || providerName.trim().isEmpty()) {
            providerName = "QWEN";
        }
        String normalizedProvider = providerName.toUpperCase();
        ChatModel chatModel = chatModelMap.get(normalizedProvider);
        if (chatModel == null) {
            log.warn("未找到提供商 {} 的ChatModel，使用默认ChatModel", normalizedProvider);
            // 3.如果找不到，取第一个
            chatModel = chatModelMap.values()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("没有可用的ChatModel"));
        }

        log.debug("为提供商 {} 创建ChatClient", normalizedProvider);

        // 使用Spring AI的ChatClient构建器，添加默认配置
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个专业的代码审查专家。")
                .build();
    }

    /**
     * 创建默认ChatClient
     */
    public ChatClient createDefaultChatClient() {
        return createChatClient("QWEN");
    }

    /**
     * 获取可用的模型提供商列表
     *
     * @return 提供商名称列表
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(chatModelMap.keySet());
    }


}
