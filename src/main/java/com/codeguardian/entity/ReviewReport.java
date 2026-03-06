package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 代码审查报告
 */
@Entity
@Table(name = "review_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewReport {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 关联的审查任务ID
     */
    @Column(name = "task_id", nullable = false, unique = true)
    private Long taskId;
    
    /**
     * 报告内容（HTML格式）
     */
    @Column(columnDefinition = "TEXT")
    private String htmlContent;
    
    /**
     * 报告内容（Markdown格式）
     */
    @Column(columnDefinition = "TEXT")
    private String markdownContent;
    
    /**
     * 统计信息（JSON格式）
     */
    @Column(columnDefinition = "TEXT")
    private String statistics;
    
    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

