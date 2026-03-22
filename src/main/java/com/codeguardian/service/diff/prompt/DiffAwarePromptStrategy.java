package com.codeguardian.service.diff.prompt;

import com.codeguardian.service.diff.model.ChangeUnit;

/**
 * Diff 感知 Prompt 构建策略接口
 * Webhook → WebhookPromptStrategy（Markdown 输出）
 * CI/CD   → CicdPromptStrategy（严格 JSON 输出）
 */
public interface DiffAwarePromptStrategy {

    /**
     * 根据 ChangeUnit 构建完整 Prompt 文本
     *
     * @param unit          变更单元（含代码、diff、RAG 上下文）
     * @param commitMessage 本次 commit 信息（可为 null）
     * @return 完整 Prompt 字符串，直接传给 ChatClient
     */
    String buildPrompt(ChangeUnit unit, String commitMessage);
}
