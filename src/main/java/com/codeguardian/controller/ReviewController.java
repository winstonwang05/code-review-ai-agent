package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.codeguardian.dto.GitCloneResponseDTO;
import com.codeguardian.dto.GitFileResponseDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.GitService;
import com.codeguardian.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @description: 代码审查控制器
 * @author: Winston
 * @date: 2026/2/26 23:12
 * @version: 1.0
 */
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
@Slf4j
public class ReviewController {
    private final ReviewService reviewService;
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final GitService gitService;



    /**
     * 审查代码片段
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/snippet")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewSnippet(@Valid  @RequestBody ReviewRequestDTO request) {
        request.setReviewType("SNIPPET");
        // 1.创建任务
        ReviewResponseDTO reviewTask = reviewService.createReviewTask(request);
        // 2.返回结果
        return ResponseEntity.ok(reviewTask);
    }

    /**
     * 审查单个文件
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/file")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewFile(@jakarta.validation.Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("FILE");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 审查指定目录
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/directory")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewDirectory(@jakarta.validation.Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("DIRECTORY");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 审查整个项目
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/project")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewProject(@jakarta.validation.Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("PROJECT");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 审查Git项目
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/git")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewGitProject(@jakarta.validation.Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("GIT");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取审查任务详情
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/task/{taskId}")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewResponseDTO> getTask(@PathVariable("taskId") Long taskId) {
        ReviewResponseDTO reviewTask = reviewService.getReviewTask(taskId);
        return ResponseEntity.ok(reviewTask);
    }

    /**
     * 下载Git项目并返回文件列表（用于前端显示文件树）
     * 显示克隆下来并排除黑名单的文件列表
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/git/clone")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<GitCloneResponseDTO> cloneGitRepository(@RequestBody ReviewRequestDTO request) {
        try {
            String gitUrl = request.getGitUrl();
            String username = request.getGitUsername();
            String password = request.getGitPassword();

            if (gitUrl == null || gitUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(GitCloneResponseDTO.builder()
                        .success(false)
                        .error("Git仓库地址不能为空")
                        .build());
            }

            // 1.首先远程克隆URL并返回对应的临时目录
            String localPath = gitService.cloneRepository(gitUrl, username, password);

            // 获取配置的范围，白名单和黑名单
            String includePaths = configService.getSettings().getIncludePaths();
            String excludePaths = configService.getSettings().getExcludePaths();


            // 2.解析并读取临时目录下的所有文件，包括排除黑名单
            List<String> fileList = gitService.getFileList(localPath, includePaths, excludePaths);
            return ResponseEntity.ok(GitCloneResponseDTO.builder()
                    .localPath(localPath)
                    .fileList(fileList)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("克隆Git仓库失败", e);
            return ResponseEntity.status(500).body(GitCloneResponseDTO.builder()
                    .success(false)
                    .error("克隆Git仓库失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 读取Git项目中的文件内容,单个文件的内容返回
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/git/file")
    @SaCheckPermission("QUERY")
    public ResponseEntity<GitFileResponseDTO> readGitFile(@RequestParam("path") String filePath) {
        try {
            // 1.获取当个文件下的内容
            String content = gitService.readFile(filePath);
            // 2.构建返回
            return ResponseEntity.ok(GitFileResponseDTO.builder()
                    .content(content)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("读取Git文件失败", e);
            return ResponseEntity.status(500).body(GitFileResponseDTO.builder()
                    .success(false)
                    .error("读取文件失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 查询审查历史，需要实体类：task， finding
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/history")
    @SaCheckPermission("QUERY")
    public ResponseEntity<Page<ReviewResponseDTO>> getHistory(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "reviewType", required = false) String reviewType,
            @RequestParam(value = "startTime", required = false) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false) LocalDateTime endTime,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "DESC") String sortDir) {
        // 1.先根据分页条件查询出Task实体类，一次查询
        Sort sort = sortDir.equalsIgnoreCase("ASC") ?
                Sort.by(sortBy).ascending() :
                Sort.by(sortBy).descending();
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        Integer reviewTypeCode = reviewType != null && !reviewType.isEmpty()
                ? ReviewTypeEnum.fromName(reviewType).getValue()
                : null;
        Page<ReviewTask> tasks = taskRepository.findByConditions(name, reviewTypeCode, startTime, endTime, pageRequest);
        if (!tasks.hasContent()) {
            return ResponseEntity.ok(Page.empty(pageRequest));
        }
        // 2.提取出taskIds去批量查询finding并按照任务id分组，二次查询
        List<Long> taskIds = tasks.getContent().stream().map(ReviewTask::getId).toList();
        // 分组结果 key ： 任务id value： 对应的finding集合
        Map<Long, List<Finding>> findingsByTaskId = findingRepository.findByTaskIdIn(taskIds)
                .stream()
                .collect(Collectors.groupingBy(Finding::getTaskId));
        // 3.构建结果返回，防止N + 1查询，N是指获取每一个任务id去查询对应的finding，直接从分组结果中取
        Page<ReviewResponseDTO> responseDTOS = tasks.map(task -> {
            List<Finding> findings = findingsByTaskId.getOrDefault(task.getId(), Collections.emptyList());
            return ReviewResponseDTO.builder()
                    .taskId(task.getId())
                    .taskName(ReviewTypeEnum.fromValue(task.getReviewType()) == ReviewTypeEnum.GIT && task.getScope() != null ? task.getScope() : task.getName())
                    .status(TaskStatusEnum.fromValue(task.getStatus()).name())
                    .reviewType(ReviewTypeEnum.fromValue(task.getReviewType()).name())
                    .scope(mapScopeLabelByType(task.getReviewType()))
                    .createdAt(task.getCreatedAt())
                    .totalFindings(findings != null ? findings.size() : 0)
                    .criticalCount(countBySeverity(findings, SeverityEnum.CRITICAL.getValue()))
                    .highCount(countBySeverity(findings, SeverityEnum.HIGH.getValue()))
                    .mediumCount(countBySeverity(findings, SeverityEnum.MEDIUM.getValue()))
                    .lowCount(countBySeverity(findings, SeverityEnum.LOW.getValue()))
                    .build();
        });
        return ResponseEntity.ok(responseDTOS);
    }

    private int countBySeverity(List<Finding> findings, Integer severity) {
        long severityCount = findings.stream()
                .filter(finding ->
                        severity.equals(finding.getSeverity()))
                .count();
        return (int) severityCount;
    }

    private String mapScopeLabelByType(Integer reviewType) {
        ReviewTypeEnum e = ReviewTypeEnum.fromValue(reviewType);
        if (e == ReviewTypeEnum.PROJECT) return "整个项目";
        if (e == ReviewTypeEnum.DIRECTORY) return "指定目录";
        if (e == ReviewTypeEnum.FILE) return "指定文件";
        if (e == ReviewTypeEnum.GIT) return "git项目";
        return "代码片段";
    }



    /**
     * 删除任务
     * <p>需`REVIEW`权限。</p>
     */
    @DeleteMapping("/task/{taskId}")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<Void> deleteTask(@PathVariable("taskId") Long taskId) {
        Optional<ReviewTask> reviewTask = taskRepository.findById(taskId);
        if (reviewTask.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        taskRepository.deleteById(taskId);
        return ResponseEntity.ok().build();
    }



}
