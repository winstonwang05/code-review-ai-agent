package com.codeguardian.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @description: 代码解析服务，包括读取和扫描
 * @author: Winston
 * @date: 2026/3/2 19:04
 * @version: 1.0
 */
@Slf4j
@Service
public class CodeParserService {


    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    /**
     * 优雅停机
     */
    @jakarta.annotation.PreDestroy
    public void destroy() {
        executor.shutdown();
    }


    /**
     * 读取文件内容
     */
    public String readFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("文件不存在: " + path.toAbsolutePath() + " (输入路径: " + filePath + ")");
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + filePath, e);
        }
    }

    /**
     * 读取目录下的所有代码文件
     */
    public String readDirectory(String directoryPath) {
        try {
            // 1.判断路径是否为空，如果为空，智能处理判断是否少了src/路径，因为系统默认是在根目录下，无法检查到main
            Path dir = Paths.get(directoryPath);
            if  (!Files.exists(dir) || !Files.isDirectory(dir)) {
                String suggestion = "";
                try {
                    // 拼接“src/”，判断是否存在
                    Path srcDir = Paths.get("src", directoryPath);
                    if  (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
                        suggestion = " 检测到 " + srcDir.toAbsolutePath() + " 存在，您是否指的是 src/" + directoryPath + " ?";
                    }
                } catch (Exception e) {}
                throw new IllegalArgumentException("目录不存在: " + dir.toAbsolutePath() + " (输入路径: " + directoryPath + ")." + suggestion);
            }
            // 2.递归调用获取文件夹下所有文件路径
            List<Path> files;
            try (Stream<Path> paths = Files.walk(dir)){
                files = paths.filter(Files::isRegularFile).filter(this::isCodeFile).toList();
            }

            if (files.isEmpty()) {
                return "";
            }

            // 3.通过异步线程池执行每一个文件的内容添加至Future，保障结果完整性
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (Path path : files) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return Files.readString(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, executor).exceptionally(ex -> {
                    log.warn("读取文件失败: {}", path, ex);
                    return null;
                }));
            }
            // 4.主线程获取每一个文件的内容，通过join阻塞等待
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < futures.size(); i++) {
                String fileContent = futures.get(i).join();
                if (fileContent != null) {
                    content.append("=== ")
                            .append(files.get(i).toString())
                            .append(" ===\n")
                            .append(fileContent)
                            .append("\n\n");
                }
            }
            return content.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 扫描目录下的所有代码文件路径（支持过滤）
     */
    public List<Path> scanDirectory(String directoryPath, String includePaths, String excludePaths) {
        try {
            if (directoryPath == null || directoryPath.trim().isEmpty()) {
                throw new IllegalArgumentException("目录路径不能为空");
            }

            Path dir = Paths.get(directoryPath).toAbsolutePath().normalize();
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("目录不存在: " + directoryPath);
            }

            log.info("正在扫描目录: {}", dir);

            // 解析配置
            List<String> includes = parsePaths(includePaths);
            List<String> excludes = parsePaths(excludePaths);

            // 过滤要求
            try (Stream<Path> paths = Files.walk(dir)){
                return paths.filter(Files::isRegularFile)
                        .filter(this::isCodeFile)
                        .filter(path -> {
                            // 掐去每一个文件的目录，获取相对目录
                            String relativePath = dir.relativize(path).toString().replace(File.separator, "/");
                            return isPathIncluded(relativePath, includes, excludes);
                        })
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.error("扫描目录失败: {}", directoryPath, e);
            throw new RuntimeException("扫描目录失败: " + directoryPath, e);
        }
    }


    /**
     * 解析路径，把前端传来的“不可控、可能带有各种人为输入瑕疵的纯文本”
     * 加工成了一个“绝对干净、没有空行、没有两端空格、分隔符完全统一的黑/白名单列表”。
     * @param pathsConfig 纯文本
     * @return 返回文件路径列表
     */
    private List<String> parsePaths(String pathsConfig) {
        if (pathsConfig == null || pathsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(pathsConfig.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                // 统一分隔符
                .map(s -> s.replace("\\", "/"))
                .collect(Collectors.toList());
    }

    /**
     * 读取项目代码
     */
    public String readProject(String projectPath) {
        return readDirectory(projectPath);
    }

    private boolean isPathIncluded(String path, List<String> includes, List<String> excludes) {
        // 1.检查排除路径
        for (String exclude : excludes) {
            if (path.startsWith(exclude) || path.contains("/" + exclude + "/")) {
                return false;
            }
        }
        // 2.检查包含路径
        if (!includes.isEmpty()) {
            boolean isIncluded = false;
            for (String include : includes) {
                if (path.startsWith(include) || path.contains("/" + include + "/")) {
                    isIncluded = true;
                    break;
                }
            }
        }
        return true;
    }
    /**
     * 判断是否为代码文件
     */
    private boolean isCodeFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
                fileName.endsWith(".js") ||
                fileName.endsWith(".ts") ||
                fileName.endsWith(".py") ||
                fileName.endsWith(".go") ||
                fileName.endsWith(".rs") ||
                fileName.endsWith(".cpp") ||
                fileName.endsWith(".c") ||
                fileName.endsWith(".cs") ||
                fileName.endsWith(".php") ||
                fileName.endsWith(".rb") ||
                fileName.endsWith(".swift") ||
                fileName.endsWith(".kt");
    }


}
