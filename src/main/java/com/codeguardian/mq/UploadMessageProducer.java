package com.codeguardian.mq;

import com.codeguardian.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件异步上传 MQ 生产者
 * 在 Controller 层读取文件字节数组，封装为 UploadMessage 投入队列，立即返回。
 * 耗时的向量化和 MinIO 上传由消费者异步完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 将文件投入上传队列
     * @param file HTTP 请求中的文件，字节在此处读取，避免请求结束后 InputStream 关闭
     */
    public void send(MultipartFile file) throws IOException {
        UploadMessage message = UploadMessage.builder()
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileBytes(file.getBytes())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.UPLOAD_EXCHANGE,
                RabbitMQConfig.UPLOAD_ROUTING_KEY,
                message);

        log.info("[UploadProducer] 文件已投入上传队列: filename={}, size={} bytes",
                file.getOriginalFilename(), file.getSize());
    }
}
