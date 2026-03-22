package com.codeguardian.service.diff.model;

/**
 * 变更类型枚举
 * ADD          - 新增文件或新增方法
 * MODIFY       - 修改已有方法
 * PARTIAL_DELETE - 方法内局部删行（方法仍存在）
 * FULL_DELETE  - 整个方法或文件被删除
 */
public enum ChangeType {
    ADD,
    MODIFY,
    PARTIAL_DELETE,
    FULL_DELETE
}
