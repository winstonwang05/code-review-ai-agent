package com.codeguardian.dto.integration;

import lombok.Data;

@Data
public class CicdTriggerRequest {
    private String gitUrl;
    private String branch;
    private String commitHash;
    private String triggerBy; // e.g., "JENKINS", "GITLAB_CI"
    private String projectPath; // 可选，指定子目录
    private String blockOn; // 阻断级别
    private Integer mrIid; // MR/PR 内部编号，用于 Diff API 拉取
}
