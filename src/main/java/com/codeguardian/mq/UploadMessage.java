package com.codeguardian.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件异步上传 MQ 消息体
 * MultipartFile 是 HTTP 请求对象，请求结束后 InputStream 关闭，不能直接序列化传递。
 * 这里在 Controller 层提前读取字节数组和元数据，封装为可序列化的消息体投入 MQ。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadMessage implements Serializable {

    /** 原始文件名 */
    private String originalFilename;

    /** MIME 类型，如 application/pdf、text/plain */
    private String contentType;

    /** 文件字节内容 */
    private byte[] fileBytes;
}
