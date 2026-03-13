package com.codeguardian.service.rag;

import io.minio.*;
import io.minio.messages.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @description: Minio对象存储服务
 * @author: Winston
 * @date: 2026/3/6 13:35
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    /**
     * 注入Minio客户端
     */
    private final MinioClient minioClient;
    /**
     * 注入桶名
     */
    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 上传字符串内容到MinIO
     * @param content 字符串内容
     * @param extension 文件扩展名（如 .html, .md, .txt）
     * @param objectPrefix 对象名称前缀（用于组织文件路径）
     * @return 存储的对象名称
     */
    public String uploadStringContent(String content, String extension, String objectPrefix) {
        try {
            // 1.判断桶是否存在，不存在则创建
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created MinIO bucket: {}", bucketName);
            }

            // 2.构建对象名称：前缀/日期/UUID.扩展名
            String dateStr = LocalDate.now().toString();
            String objectName = String.format("%s/%s/%s%s", objectPrefix, dateStr, UUID.randomUUID(), extension);

            // 3.将字符串转为字节数组并上传
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(contentBytes);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, contentBytes.length, -1)
                            .contentType(getContentType(extension))
                            .build()
            );
            log.info("Uploaded string content to MinIO: bucket={}, object={}, size={} bytes", bucketName, objectName, contentBytes.length);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload string content to MinIO", e);
            throw new RuntimeException("字符串内容上传失败", e);
        }
    }

    /**
     * 上传文件到MinIO
     * 只有第一次上传需要创建桶
     * @param file 文件
     * @return 存储的对象名称
     */
    public String uploadFile(MultipartFile file) {
        try {
            // 1.判断桶是否存在，不存在则创建
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created MinIO bucket: {}", bucketName);
            }

            // 2.创建唯一性文件名，防止重复性文件名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            // 3.文件名构成：UUID + 原文件后缀
            String objectName = UUID.randomUUID().toString() + extension;

            // 4.存储到Minio，执行上传
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            log.info("Uploaded file to MinIO: bucket={}, object={}", bucketName, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload file to MinIO", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 获取文件流
     *
     * @param objectName 对象名称
     * @return 文件输入流
     */
    public InputStream getFile(String objectName) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        }  catch (Exception e) {
            log.error("Failed to get file from MinIO: bucket={}, object={}", bucketName, objectName, e);
            throw new RuntimeException("获取文件失败", e);
        }
    }
    /**
     * 删除文件
     * @param objectName 对象名称
     */
    public void removeFile(String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            log.info("Removed file from MinIO: bucket={}, object={}", bucketName, objectName);
        } catch (Exception e) {
            log.error("Failed to remove file from MinIO", e);
            throw new RuntimeException("文件删除失败", e);
        }
    }



    public String getBucketName() {
        return bucketName;
    }

    /**
     * 根据文件扩展名获取 Content-Type
     * @param extension 文件扩展名
     * @return MIME 类型
     */
    private String getContentType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        return switch (extension.toLowerCase()) {
            case ".html", ".htm" -> "text/html";
            case ".css" -> "text/css";
            case ".js" -> "application/javascript";
            case ".json" -> "application/json";
            case ".md" -> "text/markdown";
            case ".txt" -> "text/plain";
            case ".xml" -> "application/xml";
            case ".pdf" -> "application/pdf";
            case ".zip" -> "application/zip";
            case ".java", ".py", ".c", ".cpp", ".h", ".ts" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    /**
     * 获取预签名下载URL（有效期默认1小时）
     * @param objectName 对象名称
     * @return 预签名URL
     */
    public String getPresignedDownloadUrl(String objectName) {
        return getPresignedDownloadUrl(objectName, 3600);
    }

    /**
     * 获取预签名下载URL
     * @param objectName 对象名称
     * @param expirySeconds 过期时间（秒）
     * @return 预签名URL
     */
    public String getPresignedDownloadUrl(String objectName, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(expirySeconds)
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to get presigned URL: object={}", objectName, e);
            throw new RuntimeException("获取预签名URL失败", e);
        }
    }

}
