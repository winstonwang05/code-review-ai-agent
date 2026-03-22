package com.codeguardian.dto.integration;

import lombok.Builder;
import lombok.Data;

/**
 * @description: CI/CD 响应
 * @author: Winston
 * @date: 2026/3/12 13:49
 * @version: 1.0
 */
@Data
@Builder
public class CicdStatusResponse {
    private Long taskId;
    private String taskKey;  // CI/CD 任务唯一标识（如 CI-JENKINS-1234567890）
    private String status; // PENDING, PARSING, REVIEWING, COMPLETED, FAILED, TIMEOUT
    private boolean passed; // 是否通过门禁
    private String message;
    private String reportUrl;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        private int critical;
        private int high;
        private int medium;
        private int low;
    }

}
