package com.codeguardian.service.diff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AST 解析出的方法节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodNode {
    private String methodName;
    /** 含参数列表的完整签名，如 public String findById(Long id) */
    private String signature;
    private String returnType;
    private int startLine;
    private int endLine;
    /** 完整方法体文本（含方法签名行） */
    private String body;
    /** 所属类名 */
    private String className;
    /** 方法注解列表，如 [@Override, @Transactional] */
    private List<String> annotations;
}
