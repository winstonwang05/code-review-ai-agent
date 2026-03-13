package com.codeguardian.service;

import com.codeguardian.dto.FileContentDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewReport;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.CategoryEnum;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.repository.ReviewReportRepository;
import com.codeguardian.service.rules.RuleEngineService;
import com.codeguardian.service.rag.MinioStorageService;
import com.codeguardian.util.ReviewTaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * @description: 代码审查服务
 * @author: Winston
 * @date: 2026/2/25 9:16
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final ReviewReportRepository reportRepository;
    private final AIModelService aiModelService;
    private final CodeParserService codeParserService;
    private final RuleEngineService ruleEngineService;
    private final SystemConfigService configService;
    private final GitService gitService;
    private final MinioStorageService minioStorageService;
    private final ReportService reportService;

    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final ExecutorService orchestrationExecutor = Executors.newCachedThreadPool();
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(5);

    @jakarta.annotation.PreDestroy
    public void destroy() {
        log.info("Closing review service executors...");
        executor.shutdown();
        orchestrationExecutor.shutdown();
        uploadExecutor.shutdown();
    }
    /**
     * 创建审查任务并开始审查
     */
    @Transactional
    public ReviewResponseDTO createReviewTask(ReviewRequestDTO request) {
        log.info("创建审查任务: type={}, scope={}", request.getReviewType(),
                request.getProjectPath() != null ? request.getProjectPath() :
                        request.getFilePath() != null ? request.getFilePath() : "代码片段");
        // 1.创建任务
        ReviewTask task = ReviewTask.builder()
                // 2.设置任务名字
                .name(request.getTaskName() != null ?
                        request.getTaskName() : generateTaskName(request))
                .reviewType(ReviewTypeEnum.fromName(request.getReviewType()).getValue())
                .scope(determineScope(request))
                .status(TaskStatusEnum.RUNNING.getValue())
                .createdAt(LocalDateTime.now())
                .build();
        task = taskRepository.save(task);
        // 3.设置任务范围，持久化数据信息，服务重启之后可以找回,项目/目录不需要设置，因为以及存储到服务器端了
        try {
            String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
            // 1.Snippet 逻辑，直接获取请求中的数据并设置属性
            if ("SNIPPET".equals(type)) {
                if (request.getCodeSnippet() != null && !request.getCodeSnippet().isBlank()) {
                    task.setScope(request.getCodeSnippet());
                    task = taskRepository.save(task);
                }


            }
            // 2.File逻辑，直接从请求中获取，没有就从文件列表获取第一个
            else if ("FILE".equals(type)) {
                if (request.getFilePath() != null  && !request.getFilePath().isBlank()) {
                    task.setScope(request.getFilePath());
                    task = taskRepository.save(task);
                } else if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    FileContentDTO fileContentDTO = request.getFiles().get(0);
                    if (fileContentDTO.getContent() != null) {
                        // 获取操作系统的临时目录
                        Path root = Paths.get(System.getProperty("java.io.tmpdir"),
                                "codeguardian", "uploads",
                                String.valueOf(task.getId()));
                        // 获取文件的类名
                        String rel = fileContentDTO.getPath() != null ? fileContentDTO.getPath().replace('\\', '/') : "uploaded.txt";
                        // 拼接
                        Path out = root.resolve(rel);
                        Files.createDirectories(out.getParent());
                        Files.writeString(out, fileContentDTO.getContent());
                        task.setScope(out.toString());
                        task = taskRepository.save(task);
                    }
                }
            }
            // 3.Git逻辑，直接获取路径并设置
            else if ("GIT".equals(type)) {
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    task.setScope(request.getGitUrl());
                    task = taskRepository.save(task);
                }
            }
        } catch (Exception e) {
            log.warn("保存代码样本失败，将使用默认范围标签", e);
        }
        // 4.异步线程执行审查任务
        ReviewTask finalTask = task;
        orchestrationExecutor.submit(() -> {
            try {
                performReview(finalTask, request);
                // 设置状态
                finalTask.setStatus(TaskStatusEnum.COMPLETED.getValue());
                finalTask.setCompletedAt(LocalDateTime.now());
                // 5.异步上传代码快照和报告到 MinIO
                uploadToMinioAsync(finalTask, request);
            } catch (Exception e) {
                log.error("审查任务执行失败: taskId={}", finalTask.getId(), e);
                finalTask.setStatus(TaskStatusEnum.FAILED.getValue());
                finalTask.setErrorMessage(e.getMessage());
            } finally {
                taskRepository.save(finalTask);
            }
        });
        return buildResponseDTO(finalTask);
    }

    /**
     * 构建响应DTO
     */
    private ReviewResponseDTO buildResponseDTO(ReviewTask task) {
        // 1.根据taskId查询finding表
        List<Finding> findings = findingRepository.findByTaskId(task.getId());
        if  (CollectionUtils.isEmpty(findings)) {
            findings = List.of();
        }
        // 2.使用Stream API一次性统计各级别问题数量,分组计数，key是问题类别，value是值
        Map<Integer, Integer> severityCounts = findings.stream()
                .filter(finding -> finding.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.summingInt(e -> 1)));
        // 3.设置属性返回
        return ReviewResponseDTO.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .status(TaskStatusEnum.fromValue(task.getStatus()).name())
                .reviewType(ReviewTypeEnum.fromValue(task.getReviewType()).name())
                .scope(task.getScope())
                .createdAt(task.getCreatedAt())
                .totalFindings(findings.size())
                .criticalCount(severityCounts.getOrDefault(SeverityEnum.CRITICAL.getValue(), 0))
                .highCount(severityCounts.getOrDefault(SeverityEnum.HIGH.getValue(), 0))
                .mediumCount(severityCounts.getOrDefault(SeverityEnum.MEDIUM.getValue(), 0))
                .lowCount(severityCounts.getOrDefault(SeverityEnum.LOW.getValue(), 0))
                .build();
    }

    /**
     * 执行审查
     */
    private void performReview(ReviewTask task, ReviewRequestDTO request) throws ExecutionException, InterruptedException {
        String type = request.getReviewType().toUpperCase();
        // 1.如果是目录/项目
        if ("DIRECTORY".equals(type) || "PROJECT".equals(type)) {
            performParallelReview(task, request);
        } else if ("GIT".equals(type)) {
            // 2.如果是Git，直接查看本地目录有没有，没有说明还未克隆，克隆之后动态保存到该任务范围
            if (request.getProjectPath() != null && request.getGitUrl() != null) {
                try {
                    log.info("Git项目尚未克隆，开始克隆: {}", request.getGitUrl());
                    String localPath = gitService.cloneRepository(request.getGitUrl(), request.getGitUsername(), request.getGitPassword());
                    request.setProjectPath(localPath);
                    task.setScope(localPath);
                    taskRepository.save(task); // 更新任务范围
                    log.info("Git克隆完成，本地路径: {}", localPath);
                } catch (Exception e) {
                    log.error("Git自动克隆失败", e);
                    throw new RuntimeException("Git克隆失败: " + e.getMessage());
                }
            }
            // 处理克隆下来的项目
            if (request.getProjectPath() != null) {
                performParallelReview(task, request);
            } else {
                throw new UnsupportedOperationException("Git项目未克隆或路径为空");
            }
        } else {
            // 3.如果是片段或者文件
            String codeContent = fetchCodeContent(request);
            if (codeContent == null || codeContent.trim().isEmpty()) {
                throw new IllegalArgumentException("代码内容为空");
            }
            List<Finding> findings = executeReviewStrategy(codeContent, request.getLanguage(), request);
            saveFindings(task, findings);
            log.info("审查完成: taskId={}, findingsCount={}", task.getId(), findings.size());

        }

    }
    /**
     * 获取代码内容
     */
    private String fetchCodeContent(ReviewRequestDTO request) {
        return switch (request.getReviewType().toUpperCase()) {
            case "SNIPPET" -> request.getCodeSnippet();
            case "FILE" -> {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    yield request.getFiles().get(0).getContent();
                }
                yield codeParserService.readFile(request.getFilePath());
            }
            default -> throw new IllegalArgumentException("不支持的审查类型: " + request.getReviewType());

        };
    }

    /**
     * 使用线程池并行执行审查，处理目录/项目
     */
    private void performParallelReview(ReviewTask task, ReviewRequestDTO request) throws ExecutionException, InterruptedException {
        // 1.初始化Future
        List<Future<List<Finding>>> futures = Collections.emptyList();
        if (request.getFiles() != null &&  !CollectionUtils.isEmpty(request.getFiles())) {
            // 2.如果是文件列表，则直接遍历每一个文件审查，并将findings放入Future
            log.info("使用上传的文件列表进行审查: taskId={}, count={}", task.getId(), request.getFiles().size());
            futures = request.getFiles().stream()
                    .map(file -> executor.submit(() -> reviewSingleFile(file.getPath(), file.getContent(), request)))
                    .collect(Collectors.toList());

        } else {
            // 3.如果是目录需要调用解析文件服务获取文件再审查每一个文件，并将findings放入Future

            String path = request.getProjectPath();
            if (path == null || path.trim().isEmpty()) {
                path = request.getDirectoryPath();
            }

            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("项目或目录路径不能为空");
            }
            // 3.1获取排除文件和包括文件
            String includePaths = configService.getSettings().getIncludePaths();
            String excludePaths = configService.getSettings().getExcludePaths();

            // 3.2调用解析服务解析文件夹得到每一个文件路径
            List<Path> files = codeParserService.scanDirectory(path, includePaths, excludePaths);
            log.info("开始并行审查本地目录: taskId={}, path={}, 文件数={}", task.getId(), path, files.size());

            Path pathFile = Paths.get(path).toAbsolutePath().normalize();
            futures = files.stream()
                    .map(filePath -> executor.submit(() -> {
                        try {
                            // 获取每一个文件的相对路径，侦察语言，避免路径太长
                            String content = codeParserService.readFile(filePath.toString());
                            Path relativizePath = pathFile.relativize(filePath.toAbsolutePath().normalize());
                            return reviewSingleFile(relativizePath.toString(), content, request);
                        } catch (Exception e) {
                            log.error("读取文件失败: {}", filePath, e);
                            return new ArrayList<Finding>();
                        }
                    }))
                    .collect(Collectors.toList());


        }
        // 4.Future对象调用get方法判断是否完成，由主线程完成
        List<Finding> allFindings = new ArrayList<>();
        for (Future<List<Finding>> future : futures) {
            try {
                List<Finding> findings = future.get();
                if (findings != null && !findings.isEmpty()) {
                    allFindings.addAll(findings);
                }
            } catch (Exception e) {
                log.error("获取审查结果失败", e);
            }
        }
        // 5.保存到数据库
        saveFindings(task, allFindings);
        log.info("并行审查完成: taskId={}, findingsCount={}", task.getId(), allFindings.size());
    }
    private void saveFindings(ReviewTask task, List<Finding> findings) {
        // 由于保存过了，所以这次需要确认第一次保存时候的taskId
        findings.forEach(finding -> {
            finding.setTaskId(task.getId());
            findingRepository.save(finding);
        });
    }

    /**
     * 审查每一个文件的内容
     * @param relativePath 文件的相对路径
     * @param content 审查的内容
     * @param request 请求，为了设置属性值
     * @return 返回审查后的结果
     */
    private List<Finding> reviewSingleFile(String relativePath, String content, ReviewRequestDTO request) {
        try {
            // 1.检测语言
            String language = detectLanguage(relativePath);
            // 2.执行审查
            List<Finding> findings = executeReviewStrategy(language, content, request);
            // 3.设置finding属性
            findings.forEach(finding -> {
                finding.setLocation(relativePath + ": " + finding.getLocation());
            });
            return findings;
        }  catch (Exception e) {
            log.error("文件审查失败: {}", relativePath, e);
            return new ArrayList<>();
        }
    }



    /**
     * 执行审查策略（规则引擎或AI模型）
     */
    private List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request) {
        boolean userRulesOnly = Boolean.TRUE.equals(request.getRulesOnly());
        List<Finding> findings = Collections.emptyList();
        // 1.使用规则引擎
        if  (userRulesOnly) {
            // 1.1如果是传统的规则
            if ("CUSTOM".equalsIgnoreCase(request.getRuleTemplate())) {
                findings = ruleEngineService.reviewWithCustom(codeContent, request.getCustomRules());

            } else {
                // 1.2如果是自定义的规则
                findings = ruleEngineService.reviewWithTemplate(codeContent, language, request.getRuleTemplate());

            }
            // 规则引擎模式下手动标记来源
            if (findings != null) {
                findings.forEach(f -> f.setSource("RuleEngine"));
            }
        } else {
            // 2.调用AI模型进行审查
            findings = aiModelService.reviewCode(
                    codeContent,
                    language,
                    request.getModelProvider(),
                    request.getEnableRag() != null ? request.getEnableRag() : true
            );
        }

        if (findings == null) {
            return Collections.emptyList();
        }
        // 3.将得到的finding根据配置过滤掉不需要显示的问题种类
        // 3.1调用配置服务获取SettingDTO的种类设置， key就是问题种类，value是布尔类型
        Map<String, Boolean> configSettings = Collections.emptyMap();
        try {
            configSettings = configService.getSettings().getRuleCategories();
        } catch (Exception e) {
            log.warn("获取系统配置失败，将默认显示所有结果", e);
        }
        final Map<String, Boolean> finalConfig = configSettings;
        List<Finding> findingList = findings.stream()
                // 将每一个finding 的category都覆盖掉，防止出现不同模型或者规则处理的category大小写不一，这里是实现统一
                .peek(f -> {
                    CategoryEnum category = CategoryEnum.fromRaw(f.getCategory());
                    f.setCategory(category.getCategory());
                })
                // 3.2将每一个finding进行过滤，false的会被去除
                .filter(
                    finding -> Boolean.TRUE.equals(finalConfig.getOrDefault(finding.getCategory(), true))
                )
                .collect(Collectors.toList());
        return findingList;

    }







    /**
     * 生成任务名称
     *
     * <p>项目/目录/文件/Git 类型优先使用路径或仓库名，保证仪表盘展示清晰名称。</p>
     *
     * @param request 审查请求
     * @return 任务名称
     */
    private String generateTaskName(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
        switch (type) {
            // Project逻辑
            case "PROJECT" : {
                // 直接从路径中获取
                String base = extractBaseName(request.getProjectPath());
                if (base != null || !base.isEmpty()) {
                    return base;
                }
                break;
            }

            // Directory逻辑
            case "DIRECTORY" : {
                // 1.先直接从请求中获取，如果没有下一步
                String dirPath = request.getDirectoryPath();
                if (dirPath != null && !dirPath.isEmpty()) {
                    String normalized = dirPath.replace('\\', '/');
                    if (normalized.contains("/")) {
                        return normalized;
                    }
                }
                // 2.没有路径说明是多个文件列表，获取共同的目录
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    List<String> dirs = request.getFiles().stream()
                            .map(p -> p.getPath())
                            .filter(p -> p != null && !p.isBlank())
                            .map(p -> p.replace('\\', '/'))
                            .map(p -> {
                                int last = p.lastIndexOf('/');
                                return last >= 0 ? p.substring(0, last) : "";
                            })
                            .collect(Collectors.toList());
                    String common = computeCommonDir(dirs);
                    if (common != null && !common.isEmpty()) {
                        return common;
                    }
                    if (dirPath != null && !dirPath.isEmpty()) {
                        return dirPath;
                    }
                    break;
                }
            }
            // File逻辑
            case "FILE" : {
                // 直接从路径中获取
                String base = extractBaseName(request.getFilePath());
                if  (base != null && !base.isEmpty()) {
                    return base;
                }
                break;
            }
            // Snippet逻辑
            case "SNIPPET" : {
                // 猜测出名字，通过正则表达式获取类名或者方法名
                String identifier = ReviewTaskUtils.guessSnippetDisplayName(request.getCodeSnippet(), request.getLanguage());
                if (identifier != null && !identifier.isEmpty()) {
                    return identifier;
                }
                break;
            }
            // Git逻辑
            case "GIT" : {
                // 从请求中获取，如果没有则获取仓库名
                if (request.getProjectPath() != null && !request.getProjectPath().trim().isEmpty()) {
                    String base = extractBaseName(request.getProjectPath());
                    if  (base != null && !base.isEmpty()) {
                        return base;
                    }
                }
                String repo = extractRepoNameFromUrl(request.getGitUrl());
                if (repo != null && !repo.isEmpty()) {
                    return repo;
                }
                // 兜底
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    return request.getGitUrl();
                }
                break;
            }
            default :
                break;
        }
        // 兜底方案，通过UUID生成
        String prefix = switch (type) {
            case "PROJECT" -> "项目审查";
            case "DIRECTORY" -> "目录审查";
            case "FILE" -> "文件审查";
            case "SNIPPET" -> "代码片段审查";
            case "GIT" -> "Git项目审查";
            default -> "代码审查";
        };
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }


    /**
     * 获取多个文件的共同目录并返回
     */
    private String computeCommonDir (List<String> paths) {
        // 1.判空处理
        if (paths == null  || paths.isEmpty()) {
            return null;
        }
        // 2.将每一个文件的路径通过 “/” 拆分为字符串数组
        List<String[]> segments = paths.stream()
                .map(p -> p.split("/"))
                .collect(Collectors.toList());
        // 3.找出字符串数组中长度最短的数量
        int minLen = segments.stream().mapToInt(s -> s.length).min().orElse(0);
        if  (minLen == 0) {
            return null;
        }
        // 4.遍历比较每一个字符串数组中的元素
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < minLen; i++) {
            String seg = segments.get(0)[i];
            boolean allSame = true;
            for (int j = 1; j < segments.size(); j++) {
                if (!seg.equals(segments.get(j)[i])) {
                    allSame = false;
                    break;
                }
            }
            if (!allSame) {
                break;
            }
            if (stringBuilder.length() > 0) {
                stringBuilder.append("/");
            }
            stringBuilder.append(seg);
        }
        return stringBuilder.toString();
    }

    /**
     * 从路径中提取末级名称
     *
     * @param path 路径
     * @return 末级名称（目录名或文件名），路径为空时返回 null
     */
    private String extractBaseName(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(path).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 获取审查任务详情
     */
    public ReviewResponseDTO getReviewTask(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));

        return buildResponseDTO(task);
    }


    /**
     * 检测该文件是什么语言
     * @param pathStr 文件路径
     * @return 返回对应的语言
     */
    private String detectLanguage(String pathStr) {
        String fileName = Paths.get(pathStr).getFileName().toString().toLowerCase();
        if (fileName.endsWith(".java")) {
            return "Java";
        }
        if (fileName.endsWith(".py")) {
            return "Python";
        }
        if (fileName.endsWith(".js")) {
            return "JavaScript";
        }
        if (fileName.endsWith(".ts")) {
            return "TypeScript";
        }
        if (fileName.endsWith(".go")) {
            return "Go";
        }
        if (fileName.endsWith(".rs")) {
            return "Rust";
        }
        if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h")) {
            return "C/C++";
        }
        return "Unknown";
    }

    /**
     * 从 Git 仓库URL中解析仓库名
     *
     * @param url 仓库URL
     * @return 仓库名（去掉 .git 后缀），解析失败返回 null
     */
    private String extractRepoNameFromUrl(String url) {
        // 1.判空
        if  (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            // 2.去空格
            String trimmed = url.trim();
            // 3.将末尾的“.git”去除
            if (trimmed.endsWith(".git")) trimmed = trimmed.substring(0, trimmed.length() - 4);
            // 4.获取最后一个“/”或者“\\”，该符号后面就是仓库名
            int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            if (slash > 0 && slash < trimmed.length() - 1) {
                trimmed = trimmed.substring(slash + 1);
            }
            // 5.兜底方案直接返回原字符串
            return trimmed;
        } catch (Exception e) {
            return null;
        }

    }

    /**
     * 异步上传代码快照和报告到 MinIO
     * @param task 审查任务
     * @param request 审查请求
     */
    private void uploadToMinioAsync(ReviewTask task, ReviewRequestDTO request) {
        uploadExecutor.submit(() -> {
            try {
                // 1. 上传代码快照
                String codeSnapshot = fetchCodeContent(request);
                if (codeSnapshot != null && !codeSnapshot.trim().isEmpty()) {
                    String codeSnapshotFile = minioStorageService.uploadStringContent(
                            codeSnapshot, ".txt", "code-snapshots/" + task.getId()
                    );
                    log.info("Code snapshot uploaded to MinIO: taskId={}, file={}", task.getId(), codeSnapshotFile);

                    // 2. 更新报告实体中的代码快照文件引用
                    updateReportWithCodeSnapshot(task.getId(), codeSnapshotFile);
                }

                // 3. 使用 ReportService 生成报告（会同时生成 HTML 和 Markdown）
                ReviewReport report = reportService.generateReport(task.getId());

                // 4. 上传 HTML 报告到 MinIO
                if (report.getHtmlContent() != null && !report.getHtmlContent().isEmpty()) {
                    String htmlFile = minioStorageService.uploadStringContent(
                            report.getHtmlContent(), ".html", "reports/" + task.getId()
                    );
                    log.info("HTML report uploaded to MinIO: taskId={}, file={}", task.getId(), htmlFile);

                    // 5. 上传 Markdown 报告到 MinIO
                    if (report.getMarkdownContent() != null && !report.getMarkdownContent().isEmpty()) {
                        String markdownFile = minioStorageService.uploadStringContent(
                                report.getMarkdownContent(), ".md", "reports/" + task.getId()
                        );
                        log.info("Markdown report uploaded to MinIO: taskId={}, file={}", task.getId(), markdownFile);

                        // 6. 更新报告实体中的 MinIO 文件引用
                        updateReportWithMinioFiles(task.getId(), htmlFile, markdownFile);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to upload to MinIO: taskId={}", task.getId(), e);
                // 不影响主流程，仅记录错误
            }
        });
    }

    /**
     * 更新报告实体中的代码快照文件引用
     */
    private void updateReportWithCodeSnapshot(Long taskId, String codeSnapshotFile) {
        reportRepository.findByTaskId(taskId).ifPresent(report -> {
            report.setCodeSnapshotFile(codeSnapshotFile);
            reportRepository.save(report);
        });
    }

    /**
     * 更新报告实体中的 MinIO 文件引用
     */
    private void updateReportWithMinioFiles(Long taskId, String htmlFile, String markdownFile) {
        reportRepository.findByTaskId(taskId).ifPresentOrElse(report -> {
            report.setHtmlFile(htmlFile);
            report.setMarkdownFile(markdownFile);
            reportRepository.save(report);
        }, () -> {
            // 如果报告不存在，创建新报告
            ReviewReport report = ReviewReport.builder()
                    .taskId(taskId)
                    .htmlFile(htmlFile)
                    .markdownFile(markdownFile)
                    .build();
            reportRepository.save(report);
            log.info("Created new report for taskId={} with MinIO files", taskId);
        });
    }

    private String determineScope(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "UNKNOWN";
        switch (type) {
            case "PROJECT":
                return "整个项目";
            case "DIRECTORY":
                return "指定目录";
            case "FILE":
                return "指定文件";
            case "GIT":
                return "git项目";
            case "SNIPPET":
                return "代码片段";
            default:
                return "代码片段";
        }
    }



}
