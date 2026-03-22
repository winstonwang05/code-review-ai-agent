package com.codeguardian.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 重试队列配置
 * 拓扑：
 *   webhook.retry.exchange (direct)
 *     → webhook.retry.queue
 *         [x-message-ttl = 5min]
 *         [x-dead-letter-exchange = webhook.exchange]   ← TTL 到期后路由回正常队列
 *         [x-dead-letter-routing-key = webhook.mr]
 *
 * 消费者异常时（retryCount < 3）：手动投递到 webhook.retry.exchange
 * 5 分钟后消息自动重新进入 webhook.queue，retryCount 已在消息体中 +1
 */
@Configuration
public class RetryQueueConfig {

    public static final String WEBHOOK_RETRY_EXCHANGE = "webhook.retry.exchange";
    public static final String WEBHOOK_RETRY_QUEUE    = "webhook.retry.queue";
    public static final String WEBHOOK_RETRY_KEY      = "webhook.retry";

    /** 重试延迟：5 分钟 */
    private static final int RETRY_TTL_MS = 300_000;

    @Bean
    public DirectExchange webhookRetryExchange() {
        return new DirectExchange(WEBHOOK_RETRY_EXCHANGE, true, false);
    }

    @Bean
    public Queue webhookRetryQueue() {
        return QueueBuilder.durable(WEBHOOK_RETRY_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                // TTL 到期后路由回正常 Webhook 队列
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.WEBHOOK_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RabbitMQConfig.WEBHOOK_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding webhookRetryBinding() {
        return BindingBuilder.bind(webhookRetryQueue())
                .to(webhookRetryExchange())
                .with(WEBHOOK_RETRY_KEY);
    }
}
