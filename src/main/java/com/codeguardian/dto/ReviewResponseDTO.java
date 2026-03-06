package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 代码审查响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponseDTO {
    
    /**
     * 任务ID
     */
    private Long taskId;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 任务状态
     */
    private String status;

    /**
     * 审查类型
     */
    private String reviewType;
    
    /**
     * 审查范围
     */
    private String scope;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 问题总数
     */
    private Integer totalFindings;
    
    /**
     * 严重问题数
     */
    private Integer criticalCount;
    
    /**
     * 高优先级问题数
     */
    private Integer highCount;
    
    /**
     * 中优先级问题数
     */
    private Integer mediumCount;
    
    /**
     * 低优先级问题数
     */
    private Integer lowCount;
}

