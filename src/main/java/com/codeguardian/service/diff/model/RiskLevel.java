package com.codeguardian.service.diff.model;

/**
 * 变更风险等级，驱动 Prompt 审查松紧度
 * LOW    - 小型变更（方法行数 <30 / 变化行 <20）
 * MEDIUM - 中型变更（方法行数 30-80 / 变化行 20-100）
 * HIGH   - 大型变更（方法行数 >80 / 变化行 >100 / 爆炸式变更）
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
