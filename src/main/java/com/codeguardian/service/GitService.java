package com.codeguardian.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @description: Git服务
 * @author: Winston
 * @date: 2026/3/1 11:15
 * @version: 1.0
 */
@Slf4j
@Service
public class GitService {

    private static final String TEMP_DIR_PREFIX = "git_repo_";
    /**
     * key:gitUrl -> value:localPath
     */
    private final Map<String, String> clonedRepos = new HashMap<>();


    /**
     * 克隆Git仓库到临时目录
     * @param gitUrl Git仓库地址
     * @param username 用户名（可选）
     * @param password 密码/Token（可选）
     * @return 返回存储在本地的临时目录
     */
    public String cloneRepository(String gitUrl, String username, String password) {
        try {
            // 1.检查是否被克隆过，如果被克隆过，直接返回该URL的本地临时目录
            if (clonedRepos.containsKey(gitUrl)) {
                String existingPath = clonedRepos.get(gitUrl);
                // 检查本地目录是否存在
                if (Files.exists(Paths.get(existingPath))) {
                    return existingPath;
                } else {
                    clonedRepos.remove(gitUrl);
                }
            }
            // 2.如果没有被克隆，执行克隆
            // 3.创建一个随机的本地临时目录
            Path tempDir = Files.createDirectory(Path.of(TEMP_DIR_PREFIX));
            String tempDirPath = tempDir.toAbsolutePath().toString();
            String cloneUrl = gitUrl;
            // 4.构建Git命名 格式：协议://用户名:密码@主机名:端口/路径?查询参数#引用
            if (username != null && !username.trim().isEmpty() && !username.trim().equals("")) {
                // 5.如果用户名不为空，则需要编码处理，将密码里的特殊字符转义
                // 将用户名和密码插入协议和主机之间
                try {
                    URI uri = new URI(gitUrl);
                    String scheme = uri.getScheme();
                    String host = uri.getHost();
                    String path = uri.getPath();
                    int port = uri.getPort();
                    String fragment = uri.getFragment();
                    String query = uri.getQuery();
                    StringBuilder urlBuilder = new StringBuilder();
                    urlBuilder.append(scheme).append("://");
                    String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
                    if (password != null && !password.trim().isEmpty()) {
                        String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
                        // 拼接
                        urlBuilder.append(encodedUsername).append(":").append(encodedPassword).append("@");
                    } else  {
                        urlBuilder.append(encodedUsername).append("@");
                    }

                    // 添加主机
                    urlBuilder.append(host);

                    // 添加端口（如果有）
                    if (port != -1) {
                        urlBuilder.append(":").append(port);
                    }

                    // 添加路径
                    if (path != null) {
                        urlBuilder.append(path);
                    }

                    // 添加查询字符串（如果有）
                    if (query != null && !query.isEmpty()) {
                        urlBuilder.append("?").append(query);
                    }

                    // 添加引用（如果有）
                    if (fragment != null && !fragment.isEmpty()) {
                        urlBuilder.append("#").append(fragment);
                    }

                    cloneUrl = urlBuilder.toString();
                } catch (Exception e) {
                    log.warn("解析URL失败，使用简单替换方式: {}", e.getMessage());
                    // 如果URL解析失败，使用简单替换方式
                    if (gitUrl.startsWith("https://")) {
                        String auth = username + (password != null && !password.trim().isEmpty() ? ":" + password : "");
                        cloneUrl = gitUrl.replace("https://", "https://" + auth + "@");
                    } else if (gitUrl.startsWith("http://")) {
                        String auth = username + (password != null && !password.trim().isEmpty() ? ":" + password : "");
                        cloneUrl = gitUrl.replace("http://", "http://" + auth + "@");
                    }
                }
            }
            // 获取git仓库名，用于创建目录
            String repoName = gitUrl.substring(gitUrl.lastIndexOf('/') + 1);
            if (repoName.endsWith(".git")) {
                repoName = repoName.substring(0, repoName.length() - 4);
            }
            String repoPath = tempDirPath + File.separator + repoName;
            // 6.执行克隆GitURL（git clone会在tempDir下创建repoName目录）
            ProcessBuilder processBuilder = new ProcessBuilder("git", "clone", cloneUrl, repoPath);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            // 7.克隆过程日志输出
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Git clone失败: {}", output.toString());
                // 清理临时目录
                deleteDirectory(tempDir.toFile());
                throw new RuntimeException("Git clone失败: " + output.toString());
            }
            // 8.获取克隆后的临时目录返回
            File repoDir = new File(repoPath);
            if (repoDir.exists() && repoDir.isDirectory()) {
                clonedRepos.put(gitUrl, repoPath);
                log.info("Git仓库克隆成功: {} -> {}", gitUrl, repoPath);
                return repoPath;
            }
            // 如果指定路径不存在，尝试查找其他目录
            File[] files = tempDir.toFile().listFiles();
            if  (files != null && files.length > 0) {
                // 取第一个
                File foundDir = files[0];
                if (foundDir.isDirectory()) {
                    clonedRepos.put(gitUrl, foundDir.getAbsolutePath());
                    log.info("Git仓库克隆成功: {} -> {}", gitUrl, foundDir.getAbsolutePath());
                    return foundDir.getAbsolutePath();
                }
            }
            throw new RuntimeException("Git clone完成但未找到仓库目录");

        } catch (IOException | InterruptedException e) {
            log.error("克隆Git仓库失败: {}", gitUrl, e);
            throw new RuntimeException("克隆Git仓库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取目录下的所有文件列表（用于构建文件树）
     * @param directoryPath 目录路径
     * @return 文件路径列表
     */
    public List<String> getFileList(String directoryPath) {
        return getFileList(directoryPath, null, null);
    }


    /**
     * 获取目录下的所有文件列表（支持过滤）
     * @param directoryPath 目录路径
     * @param includePaths 包含路径配置（换行符分隔）
     * @param excludePaths 排除路径配置（换行符分隔）
     * @return 文件路径列表
     */
    public List<String> getFileList(String directoryPath, String includePaths, String excludePaths) {
        // 1.解析包含和不包含的路径
        List<String> includes = parsePaths(includePaths);
        List<String> excludes = parsePaths(excludePaths);
        List<String> fileList = new ArrayList<>();
        try {
            // 2.获取文件路径
            Path dir = Paths.get(directoryPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("目录不存在: " + directoryPath);
            }
            try (Stream<Path> paths = Files.walk(dir)){
                // 3.将满足条件的文件添加到结果集中
                // 先过滤掉当前文件夹
                paths.filter(Files::isRegularFile)
                // 掐去遍历的每一个根目录比对包含路径和不包含路径
                        .forEach(path -> {
                            String relativePath = dir.relativize(path).toString().replace(File.separator, "/");
                            if (isPathIncluded(relativePath, includes, excludes)) {
                                fileList.add(relativePath);
                            }
                        });
            }
            return fileList;
        } catch (IOException e) {
            log.error("获取文件列表失败: {}", directoryPath, e);
            throw new RuntimeException("获取文件列表失败: " + e.getMessage(), e);
        }

    }

    /**
     * 解析路径，将需要审查的路径打包
     * @param pathsConfig 需要解析的路径
     * @return 返回需要审查的路径集合
     */
    private List<String> parsePaths(String pathsConfig) {
        if (pathsConfig == null || pathsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(pathsConfig.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace("\\", "/"))
                .collect(Collectors.toList());
    }


    /**
     * 读取文件内容
     * @param filePath 完整文件路径
     * @return 文件内容
     */
    public String readFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("文件不存在: " + filePath);
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private boolean isPathIncluded(String path, List<String> includes, List<String> excludes) {
        // 1.排除黑名单
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
            return isIncluded;
        }
        return true;
    }

    /**
     * 删除目录及其所有内容(递归删除)
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
