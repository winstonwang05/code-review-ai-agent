package com.codeguardian.service.diff;

import com.codeguardian.service.diff.model.MethodNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AST 解析服务
 * - Java 文件：JavaParser 解析方法节点
 * - 非 Java 文件：正则降级提取方法签名
 * - 解析 unified diff hunk 行号范围
 */
@Slf4j
@Service
public class AstParserService {

    // unified diff hunk 头部正则：@@ -oldStart,oldLen +newStart,newLen @@
    private static final Pattern HUNK_PATTERN =
            Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@", Pattern.MULTILINE);

    // 去注释正则
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("//[^\n]*");
    private static final Pattern MULTI_LINE_COMMENT   = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    /**
     * 解析 Java 源码，提取所有方法节点
     */
    public List<MethodNode> parseJavaMethods(String sourceCode) {
        List<MethodNode> methods = new ArrayList<>();
        if (sourceCode == null || sourceCode.isBlank()) return methods;

        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(sourceCode);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.warn("[AST] Java 解析失败，降级为正则: {}", result.getProblems());
                return parseNonJavaMethods(sourceCode, "Java");
            }

            CompilationUnit cu = result.getResult().get();
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration md, Void arg) {
                    super.visit(md, arg);
                    if (md.getRange().isEmpty()) return;

                    String className = md.findAncestor(ClassOrInterfaceDeclaration.class)
                            .map(c -> c.getNameAsString()).orElse("");

                    List<String> annotations = md.getAnnotations().stream()
                            .map(a -> "@" + a.getNameAsString())
                            .collect(Collectors.toList());

                    methods.add(MethodNode.builder()
                            .methodName(md.getNameAsString())
                            .signature(md.getDeclarationAsString(true, true, true))
                            .returnType(md.getTypeAsString())
                            .startLine(md.getRange().get().begin.line)
                            .endLine(md.getRange().get().end.line)
                            .body(md.toString())
                            .className(className)
                            .annotations(annotations)
                            .build());
                }
            }, null);

            log.debug("[AST] Java 解析完成，方法数={}", methods.size());
        } catch (Exception e) {
            log.warn("[AST] Java 解析异常，降级为正则: {}", e.getMessage());
            return parseNonJavaMethods(sourceCode, "Java");
        }
        return methods;
    }

    /**
     * 根据 diff hunk 行号范围，找出被变更覆盖的方法
     * 判断：方法的 [startLine, endLine] 与任意 hunk [hunkStart, hunkEnd] 有交集
     */
    public List<MethodNode> findChangedMethods(List<MethodNode> allMethods, List<int[]> hunkRanges) {
        return allMethods.stream()
                .filter(m -> hunkRanges.stream().anyMatch(hunk ->
                        m.getStartLine() <= hunk[1] && m.getEndLine() >= hunk[0]))
                .collect(Collectors.toList());
    }

    /**
     * 解析 unified diff 文本，提取所有 hunk 的新文件侧行号范围
     * 返回 [[newStart, newEnd], ...]
     */
    public List<int[]> parseHunkRanges(String diffContent) {
        List<int[]> ranges = new ArrayList<>();
        if (diffContent == null || diffContent.isBlank()) return ranges;

        Matcher m = HUNK_PATTERN.matcher(diffContent);
        while (m.find()) {
            int start = Integer.parseInt(m.group(1));
            int len   = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            // len=0 表示纯删除 hunk，新文件侧无行，跳过
            if (len > 0) {
                ranges.add(new int[]{start, start + len - 1});
            }
        }
        return ranges;
    }

    /**
     * 非 Java 文件降级：正则提取方法签名行
     * 支持 Python def、Go func、JS/TS function/arrow、通用函数头
     */
    public List<MethodNode> parseNonJavaMethods(String sourceCode, String language) {
        List<MethodNode> methods = new ArrayList<>();
        if (sourceCode == null || sourceCode.isBlank()) return methods;

        Pattern pattern = buildMethodPattern(language);
        if (pattern == null) return methods;

        String[] lines = sourceCode.split("\n", -1);
        Matcher m = pattern.matcher(sourceCode);

        while (m.find()) {
            int startLine = countLines(sourceCode, m.start()) + 1;
            // 简单估算方法结束行（找下一个同级方法或文件末尾，最多 80 行）
            int endLine = Math.min(startLine + 80, lines.length);

            // 提取方法体（从签名行到估算结束行）
            StringBuilder body = new StringBuilder();
            for (int i = startLine - 1; i < endLine && i < lines.length; i++) {
                body.append(lines[i]).append("\n");
            }

            methods.add(MethodNode.builder()
                    .methodName(extractMethodName(m.group(), language))
                    .signature(m.group().trim())
                    .startLine(startLine)
                    .endLine(endLine)
                    .body(body.toString())
                    .className("")
                    .annotations(new ArrayList<>())
                    .build());
        }
        return methods;
    }

    /**
     * 去除 Java 注释和多余空白，用于语义指纹计算
     */
    public String stripCommentsAndWhitespace(String javaCode) {
        if (javaCode == null) return "";
        String stripped = MULTI_LINE_COMMENT.matcher(javaCode).replaceAll("");
        stripped = SINGLE_LINE_COMMENT.matcher(stripped).replaceAll("");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    // ---- 私有辅助方法 ----

    private Pattern buildMethodPattern(String language) {
        if (language == null) return null;
        return switch (language.toLowerCase()) {
            case "python"     -> Pattern.compile("^\\s*def\\s+\\w+\\s*\\(", Pattern.MULTILINE);
            case "go"         -> Pattern.compile("^func\\s+(?:\\(\\w+\\s+\\*?\\w+\\)\\s+)?\\w+\\s*\\(", Pattern.MULTILINE);
            case "javascript",
                 "typescript" -> Pattern.compile(
                    "(?:^\\s*(?:async\\s+)?function\\s+\\w+|^\\s*(?:export\\s+)?(?:const|let|var)\\s+\\w+\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>)",
                    Pattern.MULTILINE);
            case "java"       -> Pattern.compile(
                    "^\\s*(?:public|private|protected|static|final|abstract|synchronized|native|default)\\s+[\\w<>\\[\\]]+\\s+\\w+\\s*\\(",
                    Pattern.MULTILINE);
            default           -> null;
        };
    }

    private String extractMethodName(String signature, String language) {
        // 从签名中提取方法名（括号前的最后一个单词）
        Pattern namePattern = Pattern.compile("(\\w+)\\s*\\(");
        Matcher m = namePattern.matcher(signature);
        String name = "";
        while (m.find()) name = m.group(1);
        return name;
    }

    private int countLines(String text, int offset) {
        int count = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
