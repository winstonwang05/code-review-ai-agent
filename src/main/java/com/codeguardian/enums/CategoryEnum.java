package com.codeguardian.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 问题类别枚举
 *
 * @author CodeGuardian Team
 * @date 2025/12/30
 */
@Getter
@AllArgsConstructor
public enum CategoryEnum {

    /**
     * 安全
     */
    SECURITY(0, "安全", "security", List.of("SECURITY", "VULNERABILITY")),

    /**
     * 性能
     */
    PERFORMANCE(1, "性能", "performance", List.of("PERFORMANCE")),

    /**
     * 缺陷
     */
    BUG(2, "缺陷", "logic_error", List.of("BUG", "LOGIC_ERROR", "ERROR")),

    /**
     * 代码风格
     */
    CODE_STYLE(3, "代码风格", "style", List.of("STYLE", "CODE_STYLE", "FORMAT")),

    /**
     * 可维护性
     */
    MAINTAINABILITY(4, "可维护性", "maintainability", List.of("MAINTAINABILITY", "REFACTOR"));

    /**
     * 枚举值
     */
    private Integer value;

    /**
     * 枚举描述
     */
    private String desc;

    /**
     * 映射到配置项的key --问题种类
     */
    private final String category;

    /**
     * 兼容 AI 返回的各种原始字符串
     */
    private final List<String> aliases;


    public static CategoryEnum fromRaw(String rawName) {
        if (rawName == null) {
            return CODE_STYLE;
        }
        String normalized = rawName.toUpperCase().trim();
        return Arrays.stream(values())
                .filter(e -> e.name().equals(normalized) || e.aliases.contains(normalized))
                .findFirst()
                .orElse(CODE_STYLE);
    }

    public static CategoryEnum fromValue(Integer value) {
        if (value == null) return CODE_STYLE;
        for (CategoryEnum e : values()) {
            if (e.value.equals(value)) return e;
        }
        return CODE_STYLE;
    }
}
