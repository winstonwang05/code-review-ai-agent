package com.codeguardian.mq;

import com.codeguardian.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @description: Webhook MQ 生产者
 * 职责：Redis Lua 防抖 + 投递消息到 RabbitMQ
 *
 * 防抖逻辑（Lua 脚本保证原子性）：
 *   - 键不存在 → 写入新 commit，返回 "SET"
 *   - 键已存在且新 commit 更新 → 覆盖，返回 "UPDATED"
 *   - 键已存在且新 commit 更旧 → 丢弃，返回 "DISCARDED"
 *
 * @author: Winston
 * @date: 2026/3/18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    /** 防抖 key 前缀，TTL 5 分钟（同一 PR 5 分钟内的旧 commit 会被丢弃） */
    private static final String DEBOUNCE_KEY_PREFIX = "webhook:debounce:pr:";
    private static final int DEBOUNCE_TTL_SECONDS = 300;

    /**
     * Lua 脚本：原子比较并更新最新 commit 指针
     * KEYS[1] = debounce key
     * ARGV[1] = commitHash
     * ARGV[2] = commitTimestamp (毫秒)
     * ARGV[3] = TTL (秒)
     */
    private static final DefaultRedisScript<String> DEBOUNCE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local newHash = ARGV[1]
            local newTs   = tonumber(ARGV[2])
            local ttl     = tonumber(ARGV[3])
            local existTs = redis.call('HGET', key, 'ts')
            if not existTs then
                redis.call('HSET', key, 'hash', newHash, 'ts', newTs)
                redis.call('EXPIRE', key, ttl)
                return 'SET'
            end
            if newTs > tonumber(existTs) then
                redis.call('HSET', key, 'hash', newHash, 'ts', newTs)
                redis.call('EXPIRE', key, ttl)
                return 'UPDATED'
            end
            return 'DISCARDED'
            """, String.class);

    /**
     * 防抖后投递 Webhook 消息
     *
     * @param message 消息体（含 mrKey、commitHash、commitTimestamp 等）
     * @return true=已投递，false=被防抖丢弃
     */
    public boolean sendWithDebounce(WebhookMessage message) {
        String debounceKey = DEBOUNCE_KEY_PREFIX + message.getMrKey();

        String result = stringRedisTemplate.execute(
                DEBOUNCE_SCRIPT,
                List.of(debounceKey),
                message.getCommitHash(),
                String.valueOf(message.getCommitTimestamp()),
                String.valueOf(DEBOUNCE_TTL_SECONDS)
        );

        if ("DISCARDED".equals(result)) {
            log.info("[Webhook防抖] MR={} commit={} 已被丢弃（存在更新的commit）",
                    message.getMrKey(), message.getCommitHash());
            return false;
        }

        log.info("[Webhook防抖] MR={} commit={} 防抖结果={}, 投递MQ",
                message.getMrKey(), message.getCommitHash(), result);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.WEBHOOK_EXCHANGE,
                RabbitMQConfig.WEBHOOK_ROUTING_KEY,
                message
        );
        return true;
    }
}