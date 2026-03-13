package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.integration.CicdStatusResponse;
import com.codeguardian.dto.integration.CicdTriggerRequest;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.QualityGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * @description: CI/CD 集成控制器
 * 提供给 Jenkins, GitLab CI, GitHub Actions 等调用
 * @author: Winston
 * @date: 2026/3/12 20:32
 * @version: 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cicd")
@RequiredArgsConstructor
public class CicdController {


    private final ReviewService reviewService;
    private final ReviewTaskRepository taskRepository;
    private final QualityGateService qualityGateService;


    /**
     * 触发审查 (CI/CD Pipeline 调用)
     */
    @PostMapping("/trigger")
    public ResponseEntity<CicdStatusResponse> triggerReview(@RequestBody CicdTriggerRequest request) {

        log.info("收到CI/CD触发请求: {}", request);

        // 1.创建任务
        ReviewRequestDTO reviewRequest = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(request.getGitUrl())
                .taskName("CI-" + (request.getTriggerBy() != null ? request.getTriggerBy() : "AUTO") + "-" + System.currentTimeMillis())
                .build();

        // 如果指定了项目子路径
        if (request.getProjectPath() != null) {
            reviewRequest.setProjectPath(request.getProjectPath());
        }

        // 2.启动执行任务（底层是异步执行的）
        ReviewResponseDTO taskResponse = reviewService.createReviewTask(reviewRequest);

        // 3.返回结果，毫秒级响应，CI工具可以通过轮询checkStatus来获取结果
        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(taskResponse.getTaskId())
                .status(com.codeguardian.enums.TaskStatusEnum.RUNNING.name())
                .passed(true) // 初始状态默认为通过
                .message("任务已提交，请轮询状态接口")
                .build());
    }


    /**
     * 检查审查状态与结果 (CI/CD Pipeline 轮询)
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<CicdStatusResponse> checkStatus(
            @PathVariable Long taskId,
            @RequestParam(required = false, defaultValue = "CRITICAL") String blockOn) {
        // 1.获取任务
        Optional<ReviewTask> reviewTaskOptional = taskRepository.findById(taskId);

        if  (reviewTaskOptional.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        ReviewTask reviewTask = reviewTaskOptional.get();

        boolean isCompleted = com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue().equals(reviewTask.getStatus());
        boolean isFailed = com.codeguardian.enums.TaskStatusEnum.FAILED.getValue().equals(reviewTask.getStatus());


        boolean passed = true;
        String message = "审查进行中...";
        CicdStatusResponse.Summary summary = null;
        // 2.如果执行任务成功结束，需要质量门禁
        if (isCompleted) {
            passed = qualityGateService.checkQualityGate(taskId, blockOn);
            message = passed ? "审查通过" : "审查未通过：存在 " + blockOn + " 级别及以上的问题";

            ReviewResponseDTO task = reviewService.getReviewTask(taskId);
            // 统计摘要
            summary  = CicdStatusResponse.Summary.builder()
                    .low(task.getLowCount())
                    .high(task.getHighCount())
                    .medium(task.getMediumCount())
                    .critical(task.getCriticalCount())
                    .build();
        } else if (isFailed) {
            passed = false;
            message = "审查任务执行失败: " + reviewTask.getErrorMessage();
        }

        // 3.封装结果返回
        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(reviewTask.getId())
                .status(com.codeguardian.enums.TaskStatusEnum.fromValue(reviewTask.getStatus()).name())
                .passed(passed)
                .message(message)
                .reportUrl("/review/report/" + reviewTask.getId()) // 假设的前端报告地址
                .summary(summary)
                .build());
    }
}
