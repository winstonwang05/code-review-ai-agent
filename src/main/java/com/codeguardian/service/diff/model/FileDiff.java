package com.codeguardian.service.diff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单文件变更信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDiff {
    /** 相对路径，如 src/main/java/com/example/Foo.java */
    private String filePath;
    /** ADD=新增文件, MODIFY=修改文件, DELETE=删除文件（文件级，后续细化为方法级） */
    private ChangeType changeType;
    /** 原始 unified diff 文本 */
    private String diffContent;
    /** 完整旧源码（MODIFY/DELETE 时有值） */
    private String oldContent;
    /** 完整新源码（ADD/MODIFY 时有值） */
    private String newContent;
    /** 从扩展名推断的语言：java/python/javascript/typescript/go/... */
    private String language;
}
