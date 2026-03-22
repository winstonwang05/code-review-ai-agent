package com.codeguardian.service.diff.model;

/**
 * 审查粒度
 * METHOD_LEVEL - 方法粒度（常规变更，独立语义指纹）
 * FILE_LEVEL   - 整体粒度（爆炸式变更 / 无方法解析 / 删除）
 */
public enum ReviewScope {
    METHOD_LEVEL,
    FILE_LEVEL
}
