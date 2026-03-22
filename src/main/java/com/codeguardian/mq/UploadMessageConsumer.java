package com.codeguardian.mq;

import com.codeguardian.config.RabbitMQConfig;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/**
 * 文件异步上传 MQ 消费者
 * 从队列接收 UploadMessage，重建 MultipartFile，执行 MinIO 上传 + 向量化入库。
 * 使用手动 ACK：处理成功才 ACK，异常时 NACK 进死信队列，避免消息丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadMessageConsumer {

    private final KnowledgeBaseService knowledgeBaseService;

    @RabbitListener(
            queues = RabbitMQConfig.UPLOAD_QUEUE,
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consume(UploadMessage message, Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String filename = message.getOriginalFilename();

        log.info("[UploadConsumer] 开始处理上传任务: filename={}, size={} bytes",
                filename, message.getFileBytes() != null ? message.getFileBytes().length : 0);
        try {
            // 字节数组重建为 MultipartFile，KnowledgeBaseService 无需改动
            byte[] fileBytes = message.getFileBytes();
            String contentType = message.getContentType();
            MultipartFile file = new MultipartFile() {
                @Override public String getName() { return "file"; }
                @Override public String getOriginalFilename() { return filename; }
                @Override public String getContentType() { return contentType; }
                @Override public boolean isEmpty() { return fileBytes == null || fileBytes.length == 0; }
                @Override public long getSize() { return fileBytes == null ? 0 : fileBytes.length; }
                @Override public byte[] getBytes() { return fileBytes == null ? new byte[0] : fileBytes; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(fileBytes == null ? new byte[0] : fileBytes); }
                @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), getBytes()); }
            };

            knowledgeBaseService.uploadDocument(file);

            channel.basicAck(deliveryTag, false);
            log.info("[UploadConsumer] 上传任务完成: filename={}", filename);

        } catch (Exception e) {
            log.error("[UploadConsumer] 上传任务失败，进入死信队列: filename={}, err={}", filename, e.getMessage(), e);
            // requeue=false：不重新入队，交由 x-dead-letter-exchange 路由到 upload.dlq
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
