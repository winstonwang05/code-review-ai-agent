package com.codeguardian.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description: RabbitMQ 队列/交换机/死信队列声明
 * 拓扑结构：
 *   webhook.exchange (direct)
 *     → webhook.queue          正常队列，消费失败超过重试次数进入死信
 *     → webhook.dlq            死信队列，人工介入
 * @author: Winston
 * @date: 2026/3/18
 */
@Configuration
public class RabbitMQConfig {

    // ---- 常量 ----
    public static final String WEBHOOK_EXCHANGE    = "webhook.exchange";
    public static final String WEBHOOK_QUEUE       = "webhook.queue";
    public static final String WEBHOOK_ROUTING_KEY = "webhook.mr";

    public static final String DLX_EXCHANGE        = "webhook.dlx";
    public static final String DLQ_QUEUE           = "webhook.dlq";
    public static final String DLQ_ROUTING_KEY     = "webhook.dead";

    // ---- 死信交换机 ----
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }

    // ---- 正常交换机和队列 ----
    @Bean
    public DirectExchange webhookExchange() {
        return new DirectExchange(WEBHOOK_EXCHANGE, true, false);
    }

    @Bean
    public Queue webhookQueue() {
        return QueueBuilder.durable(WEBHOOK_QUEUE)
                // 消息被拒绝/过期后转入死信交换机
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                // 消息最大存活时间 30 分钟，超时进死信
                .withArgument("x-message-ttl", 1800000)
                .build();
    }

    @Bean
    public Binding webhookBinding() {
        return BindingBuilder.bind(webhookQueue()).to(webhookExchange()).with(WEBHOOK_ROUTING_KEY);
    }

    // ---- 上传队列 ----
    public static final String UPLOAD_EXCHANGE    = "upload.exchange";
    public static final String UPLOAD_QUEUE       = "upload.queue";
    public static final String UPLOAD_ROUTING_KEY = "upload.file";
    public static final String UPLOAD_DLX_EXCHANGE = "upload.dlx.exchange";
    public static final String UPLOAD_DLQ_QUEUE = "upload.dlq.queue";
    public static final String UPLOAD_DLQ_ROUTING_KEY = "upload.dlq.dead";
    // 死信队列
    @Bean
    public DirectExchange uploadDLXExchange() {
        return new DirectExchange(UPLOAD_DLX_EXCHANGE, true, false);
    }
    @Bean
    public Queue uploadDLQQueue() {
        return QueueBuilder.durable(UPLOAD_DLQ_QUEUE).build();
    }
    @Bean
    public Binding uploadDLQBinding() {
        return BindingBuilder.bind(uploadDLQQueue()).to(uploadDLXExchange()).with(UPLOAD_DLQ_ROUTING_KEY);
    }

    // 正常队列
    @Bean
    public DirectExchange uploadExchange() {
        return new DirectExchange(UPLOAD_EXCHANGE, true, false);
    }
    @Bean
    public Queue uploadQueue() {
        return QueueBuilder.durable(UPLOAD_QUEUE)
                .withArgument("x-dead-letter-exchange", UPLOAD_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", UPLOAD_DLQ_ROUTING_KEY)
                .withArgument("x-message-ttl", 1800000)
                .build();
    }
    @Bean
    public Binding uploadBinding() {
        return BindingBuilder.bind(uploadQueue()).to(uploadExchange()).with(UPLOAD_ROUTING_KEY);
    }

    // ---- CI/CD 队列 ----
    public static final String CICD_EXCHANGE    = "cicd.exchange";
    public static final String CICD_QUEUE       = "cicd.queue";
    public static final String CICD_ROUTING_KEY = "cicd.review";

    // CI/CD 独立死信队列（与 Webhook DLQ 隔离，方便人工介入时区分来源）
    public static final String CICD_DLX_EXCHANGE = "cicd.dlx";
    public static final String CICD_DLQ_QUEUE    = "cicd.dlq";
    public static final String CICD_DLQ_KEY      = "cicd.dead";

    @Bean
    public DirectExchange cicdExchange() {
        return new DirectExchange(CICD_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange cicdDlxExchange() {
        return new DirectExchange(CICD_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue cicdDlqQueue() {
        return QueueBuilder.durable(CICD_DLQ_QUEUE).build();
    }

    @Bean
    public Binding cicdDlqBinding() {
        return BindingBuilder.bind(cicdDlqQueue()).to(cicdDlxExchange()).with(CICD_DLQ_KEY);
    }

    @Bean
    public Queue cicdQueue() {
        return QueueBuilder.durable(CICD_QUEUE)
                .withArgument("x-dead-letter-exchange", CICD_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CICD_DLQ_KEY)
                .withArgument("x-message-ttl", 1800000)
                .build();
    }

    @Bean
    public Binding cicdBinding() {
        return BindingBuilder.bind(cicdQueue()).to(cicdExchange()).with(CICD_ROUTING_KEY);
    }

    // ---- JSON 序列化 ----
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // 手动 ACK
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // 每次只取 1 条，公平分发
        factory.setPrefetchCount(1);
        return factory;
    }
}