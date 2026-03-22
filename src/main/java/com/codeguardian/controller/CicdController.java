package com.codeguardian.controller;

import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.integration.CicdStatusResponse;
import com.codeguardian.dto.integration.CicdTriggerRequest;
import com.codeguardian.mq.CicdMessage;
import com.codeguardian.mq.CicdMessageConsumer;
import com.codeguardian.config.RabbitMQConfig;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.QualityGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @description: CI/CD 集成控制器
 * 提供给 Jenkins, GitLab CI, GitHub Actions 等调用
 *
 * trigger  → 生成 taskKey → 写 Redis 初始状态 → 投递 MQ → 毫秒级返回 taskKey
 * status   → 先读 Redis 状态机（不查 DB），终态时再查 DB 做质量门禁
 *
 * @author: Winston
 * @date: 2026/3/12 20:32
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cicd")
@RequiredArgsConstructor
public class CicdController {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ReviewService reviewService;
    private final QualityGateService qualityGateService;

    /**
     * 触发审查 (CI/CD Pipeline 调用)
     * 毫秒级返回 taskKey，流水线凭此轮询 /status
     */
    @PostMapping("/trigger")
    public ResponseEntity<CicdStatusResponse> triggerReview(@RequestBody CicdTriggerRequest request) {
        log.info("收到CI/CD触发请求: {}", request);

        // 1. 生成全局唯一 taskKey
        String triggerBy = request.getTriggerBy() != null ? request.getTriggerBy() : "AUTO";
        String taskKey = "CI-" + triggerBy + "-" + System.currentTimeMillis();

        // 2. 写 Redis 初始状态（PENDING），供流水线立即轮询
        String statusKey = CicdMessageConsumer.TASK_STATUS_KEY_PREFIX + taskKey + ":status";
        redisTemplate.opsForValue().set(statusKey, "PENDING",
                CicdMessageConsumer.TASK_STATUS_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        // 3. 投递 MQ，异步执行审查
        CicdMessage message = CicdMessage.builder()
                .taskKey(taskKey)
                .gitUrl(request.getGitUrl())
                .projectPath(request.getProjectPath())
                .triggerBy(triggerBy)
                .mrIid(request.getMrIid())
                .commitHash(request.getCommitHash())
                .build();
        rabbitTemplate.convertAndSend(RabbitMQConfig.CICD_EXCHANGE, RabbitMQConfig.CICD_ROUTING_KEY, message);

        log.info("[CicdController] 任务已投递MQ: taskKey=", taskKey);

        // 4. 毫秒级返回，taskId 字段复用存 taskKey（String），流水线用它轮询
        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskKey(taskKey)
                .status("PENDING")
                .passed(true)
                .message("任务已提交，请轮询 /api/v1/cicd/status/" + taskKey)
                .build());
    }

    /**
     * 检查审查状态与结果 (CI/CD Pipeline 轮询)
     * 优先读 Redis，不查 DB，轻松扛高并发
     */
    @GetMapping("/status/{taskKey}")
    public ResponseEntity<CicdStatusResponse> checkStatus(
            @PathVariable String taskKey,
            @RequestParam(required = false, defaultValue = "CRITICAL") String blockOn) {

        // 1. 读 Redis 状态
        String statusKey = CicdMessageConsumer.TASK_STATUS_KEY_PREFIX + taskKey + ":status";
        String status = redisTemplate.opsForValue().get(statusKey);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        // 2. 非终态直接返回（不查 DB）
        boolean isTerminal = "COMPLETED".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
        if (!isTerminal) {
            return ResponseEntity.ok(CicdStatusResponse.builder()
                    .taskKey(taskKey)
                    .status(status)
                    .passed(true)
                    .message("审查进行中，当前状态: " + status)
                    .build());
        }

        // 3. 终态：从 Redis 取 reviewTaskId，做质量门禁
        String idKey = CicdMessageConsumer.TASK_STATUS_KEY_PREFIX + taskKey + ":reviewTaskId";
        String reviewTaskIdStr = redisTemplate.opsForValue().get(idKey);

        if (reviewTaskIdStr == null) {
            // TIMEOUT 或 FAILED 且没有 reviewTaskId
            return ResponseEntity.ok(CicdStatusResponse.builder()
                    .taskKey(taskKey)
                    .status(status)
                    .passed(false)
                    .message("审查任务异常结束: " + status)
                    .build());
        }

        Long reviewTaskId = Long.parseLong(reviewTaskIdStr);

        // 4. 质量门禁（只在终态时查一次 DB）
        boolean passed = "COMPLETED".equals(status) && qualityGateService.checkQualityGate(reviewTaskId, blockOn);
        String message = passed
                ? "审查通过"
                : "COMPLETED".equals(status)
                    ? "审查未通过：存在 " + blockOn + " 级别及以上的问题"
                    : "审查任务执行失败";

        // 5. 构建摘要
        CicdStatusResponse.Summary summary = null;
        if ("COMPLETED".equals(status)) {
            try {
                ReviewResponseDTO task = reviewService.getReviewTask(reviewTaskId);
                summary = CicdStatusResponse.Summary.builder()
                        .critical(task.getCriticalCount())
                        .high(task.getHighCount())
                        .medium(task.getMediumCount())
                        .low(task.getLowCount())
                        .build();
            } catch (Exception e) {
                log.warn("[CicdController] 获取审查摘要失败: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskKey(taskKey)
                .taskId(reviewTaskId)
                .status(status)
                .passed(passed)
                .message(message)
                .reportUrl("/review/report/" + reviewTaskId)
                .summary(summary)
                .build());
    }
}
