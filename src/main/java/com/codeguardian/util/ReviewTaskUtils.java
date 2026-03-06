package com.codeguardian.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 审查任务工具类，避免昂贵的编译过程（正则表达式）
 * @author: Winston
 * @date: 2026/2/25 9:50
 * @version: 1.0
 */
public class ReviewTaskUtils {
    // 1. 提取为静态常量：全大写命名，私有不可变
    // --- Java 匹配模具 ---
    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern JAVA_METHOD_PATTERN =
            Pattern.compile("\\b(?:public|protected|private|static|final|synchronized|abstract|native|transient|\\s)+\\s*[\\w<>\\[\\]]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    // --- Python 匹配模具 ---
    private static final Pattern PYTHON_CLASS_PATTERN =
            Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern PYTHON_METHOD_PATTERN =
            Pattern.compile("\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    // --- JS/TS 匹配模具 ---
    private static final Pattern JS_CLASS_PAT = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern JS_FUNC_PAT = Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern JS_ARROW_FUNC_PAT = Pattern.compile("\\bconst\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\(");


    // --- 通用兜底模具 ---
    private static final Pattern GENERIC_CLASS_PAT = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern GENERIC_FUNC_PAT = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");


    /**
     * 根据代码片段猜测展示名称
     */
    public static String guessSnippetDisplayName(String code, String language) {
        // 判断空
        if (code == null || code.trim().isEmpty()) {
            return "未命名片段";
        }
        String lang = language != null ? language.toLowerCase() : "";
        // 1.Java逻辑
        if (lang.equals("java")) {
            // 获取方法名
            Matcher mm = JAVA_METHOD_PATTERN.matcher(code);
            String methodName = mm.find() ? mm.group(1) : null;
            // 获取类名
            Matcher cm = JAVA_CLASS_PATTERN.matcher(code);
            String className = cm.find() ? cm.group(2) : null;
            // 如果两者都不为空，拼接返回
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            // 如果方法名为空直接返回类名
            if (className != null) return className;
        }
        // 2.Python逻辑
        else if (lang.equals("python")) {
            // 获取方法名
            Matcher cm = PYTHON_METHOD_PATTERN.matcher(code);
            String methodName = cm.find() ? cm.group(1) : null;
            // 获取类名
            Matcher pm = PYTHON_CLASS_PATTERN.matcher(code);
            String className = pm.find() ? pm.group(1) : null;
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
        }
        // 3.TS/JS 逻辑
        else if (lang.contains("typescript") || lang.contains("ts") || lang.contains("javascript") || lang.contains("js")) {
            Matcher cm = JS_CLASS_PAT.matcher(code);
            String className = cm.find() ? cm.group(1) : null;

            Matcher m1 = JS_FUNC_PAT.matcher(code);
            String methodName = m1.find() ? m1.group(1) : null;

            if (methodName == null) {
                Matcher m2 = JS_ARROW_FUNC_PAT.matcher(code);
                methodName = m2.find() ? m2.group(1) : null;
            }

            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
        }
        // 4. 通用兜底逻辑
        Matcher gc = GENERIC_CLASS_PAT.matcher(code);
        if (gc.find()) return gc.group(2);

        Matcher gf = GENERIC_FUNC_PAT.matcher(code);
        return gf.find() ? gf.group(1) : "未定义函数";

    }
}

