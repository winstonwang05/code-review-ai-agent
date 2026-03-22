package com.codeguardian.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @description: Webhook MQ 消息体
 * @author: Winston
 * @date: 2026/3/18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookMessage implements Serializable {

    /** MR 唯一标识（用于 Redis 防抖 key 和账本 key） */
    private String mrKey;

    /** Git 仓库 HTTP URL */
    private String gitUrl;

    /** 本次触发的 commit hash */
    private String commitHash;

    /** commit 时间戳（毫秒，来自 committer date，用于防抖比较） */
    private Long commitTimestamp;

    /** MR IID（GitCode/GitLab 内部编号） */
    private Integer mrIid;

    /** MR 标题 */
    private String mrTitle;

    /** Commit Message，注入 Prompt 提供变更意图上下文 */
    private String commitMessage;

    /** MR/PR 正文描述，注入 Prompt 提供开发意图上下文（比 commitMessage 更丰富） */
    private String prDescription;

    /** 重试次数（死信队列用） */
    @Builder.Default
    private int retryCount = 0;
}