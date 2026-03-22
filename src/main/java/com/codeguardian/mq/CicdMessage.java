package com.codeguardian.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @description: CI/CD MQ 消息体
 * @author: Winston
 * @date: 2026/3/18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CicdMessage implements Serializable {

    /** 全局唯一任务 ID（如 CI-9527），由 CicdController 生成后返回给流水线 */
    private String taskKey;

    /** Git 仓库 URL */
    private String gitUrl;

    /** 项目子路径（可选） */
    private String projectPath;

    /** 触发来源（Jenkins/GitLab CI/GitHub Actions） */
    private String triggerBy;

    /** MR/PR 内部编号（用于 Diff API 拉取） */
    private Integer mrIid;

    /** 触发时的 commit hash（用于 Diff API 拉取） */
    private String commitHash;

    /** 对应的 ReviewTask ID（审查任务创建后回填） */
    private Long reviewTaskId;

    /** Commit Message / PR 描述，注入 Prompt 提供变更意图上下文 */
    private String commitMessage;

    /** 质量门禁阻断策略，由流水线 POST 时传入（CRITICAL/HIGH/MEDIUM/LOW），默认 HIGH */
    @Builder.Default
    private String blockOn = "HIGH";

    /** 重试次数（DLQ 人工介入排查用） */
    @Builder.Default
    private int retryCount = 0;
}