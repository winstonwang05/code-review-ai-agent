package com.codeguardian.service.ai.tool;

import java.lang.annotation.*;

/**
 * 标注在 Function 工具类上，声明其输入类型（Request 类）。
 * 用于在 ToolRegistry 动态注册时自动解析泛型，
 * 避免因 CGLIB 代理或 Lambda 导致 getGenericInterfaces() 失效时的硬编码兜底。
 *
 * <p>符合 OCP 原则：新增工具只需在工具类上加此注解，无需修改 ToolRegistry。</p>
 *
 * @author Winston
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolInput {
    /**
     * 工具的输入类型（即 Function<I, O> 中的 I）
     */
    Class<?> value();
}
