package com.codeguardian.service.rag;

import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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



}
