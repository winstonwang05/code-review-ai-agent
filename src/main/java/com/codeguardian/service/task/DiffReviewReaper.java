package com.codeguardian.service.task;

import com.codeguardian.mq.CicdMessageConsumer;
import com.codeguardian.service.integration.GitFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Diff 审查超时扫描器
 * 每 5 分钟扫描一次，处理长时间未完成的任务
 *
 * Webhook：扫描 webhook:pr:*:meta，超过 15 分钟且 done < total → 强制汇总或标记 TIMEOUT
 * CI/CD：扫描 cicd:task:*:status 为非终态且超过 15 分钟 → 标记 TIMEOUT
 *
 * @author Winston
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiffReviewReaper {

    private final StringRedisTemplate redisTemplate;
    private final GitFeedbackService gitFeedbackService;

    /** Webhook 超时阈值：15 分钟（有重试队列，给足时间）*/
    private static final long WEBHOOK_TIMEOUT_MS = 15 * 60 * 1000L;

    /** CI/CD 超时阈值：5 分钟（流水线有时间约束，需在 stage timeout 前给出结果）*/
    private static final long CICD_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final String WEBHOOK_META_PATTERN = "webhook:pr:*:meta";
    private static final String CICD_STATUS_PATTERN  = "cicd:task:*:status";

    /**
     * 扫描 Webhook 超时任务
     * 超过 15 分钟且 done < total → 标记 TIMEOUT，更新 GitCode commit 状态
     */
    @Scheduled(fixedDelay = 300_000)
    public void sweepWebhookTimeouts() {
        try {
            Set<String> metaKeys = redisTemplate.keys(WEBHOOK_META_PATTERN);
            if (metaKeys == null || metaKeys.isEmpty()) return;

            long now = System.currentTimeMillis();
            for (String metaKey : metaKeys) {
                try {
                    String startTimeStr = (String) redisTemplate.opsForHash().get(metaKey, "startTime");
                    String totalStr     = (String) redisTemplate.opsForHash().get(metaKey, "total");
                    String doneStr      = (String) redisTemplate.opsForHash().get(metaKey, "done");

                    if (startTimeStr == null || totalStr == null || doneStr == null) continue;

                    long startTime = Long.parseLong(startTimeStr);
                    int total = Integer.parseInt(totalStr);
                    int done  = Integer.parseInt(doneStr);

                    if (now - startTime < WEBHOOK_TIMEOUT_MS) continue;
                    if (done >= total) continue; // 已完成，跳过

                    // 超时处理
                    String gitUrl    = (String) redisTemplate.opsForHash().get(metaKey, "gitUrl");
                    String commitHash = (String) redisTemplate.opsForHash().get(metaKey, "commitHash");

                    log.warn("[Reaper] Webhook 任务超时: key={}, done={}/{}", metaKey, done, total);

                    // 更新 GitCode commit 状态为失败
                    if (gitUrl != null && commitHash != null && !commitHash.isBlank()) {
                        gitFeedbackService.updateStatus(gitUrl, commitHash, "failed",
                                "CodeGuardian AI 审查超时（超过 15 分钟），请手动重新触发");
                    }

                    // 清理 Redis 账本
                    redisTemplate.delete(metaKey);

                } catch (Exception e) {
                    log.error("[Reaper] 处理 Webhook 超时任务失败: key={}, err={}", metaKey, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Reaper] sweepWebhookTimeouts 异常: {}", e.getMessage());
        }
    }

    /**
     * 扫描 CI/CD 超时任务
     * 非终态（非 COMPLETED/FAILED/TIMEOUT）且超过 15 分钟 → 标记 TIMEOUT
     */
    @Scheduled(fixedDelay = 300_000)
    public void sweepCicdTimeouts() {
        try {
            Set<String> statusKeys = redisTemplate.keys(CICD_STATUS_PATTERN);
            if (statusKeys == null || statusKeys.isEmpty()) return;

            long now = System.currentTimeMillis();
            for (String statusKey : statusKeys) {
                try {
                    String status = redisTemplate.opsForValue().get(statusKey);
                    if (status == null) continue;

                    // 已是终态，跳过
                    if ("COMPLETED".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status)) {
                        continue;
                    }

                    // 提取 taskKey：cicd:task:{taskKey}:status
                    String taskKey = extractTaskKey(statusKey);
                    if (taskKey == null) continue;

                    String startTimeKey = CicdMessageConsumer.TASK_STATUS_KEY_PREFIX + taskKey + ":startTime";
                    String startTimeStr = redisTemplate.opsForValue().get(startTimeKey);
                    if (startTimeStr == null) continue;

                    long startTime = Long.parseLong(startTimeStr);
                    if (now - startTime < CICD_TIMEOUT_MS) continue;

                    // 超时，标记 TIMEOUT
                    log.warn("[Reaper] CI/CD 任务超时: taskKey={}, status={}", taskKey, status);
                    redisTemplate.opsForValue().set(statusKey, "TIMEOUT",
                            CicdMessageConsumer.TASK_STATUS_TTL_SECONDS, TimeUnit.SECONDS);

                } catch (Exception e) {
                    log.error("[Reaper] 处理 CI/CD 超时任务失败: key={}, err={}", statusKey, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Reaper] sweepCicdTimeouts 异常: {}", e.getMessage());
        }
    }

    /**
     * 从 status key 提取 taskKey
     * cicd:task:{taskKey}:status → {taskKey}
     */
    private String extractTaskKey(String statusKey) {
        // cicd:task:CI-JENKINS-1234567890:status
        String prefix = CicdMessageConsumer.TASK_STATUS_KEY_PREFIX;
        String suffix = ":status";
        if (statusKey.startsWith(prefix) && statusKey.endsWith(suffix)) {
            return statusKey.substring(prefix.length(), statusKey.length() - suffix.length());
        }
        return null;
    }
}
