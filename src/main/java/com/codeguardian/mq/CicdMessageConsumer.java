package com.codeguardian.mq;

import com.codeguardian.config.RabbitMQConfig;
import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.config.ChatClientFactory;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.service.diff.ChangeAnalyzer;
import com.codeguardian.service.diff.DiffFetchService;
import com.codeguardian.service.diff.SemanticFingerprintService;
import com.codeguardian.service.diff.model.ChangeUnit;
import com.codeguardian.service.diff.model.FileDiff;
import com.codeguardian.service.diff.prompt.CicdPromptStrategy;
import com.codeguardian.service.integration.QualityGateService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CI/CD MQ 消费者（Diff-based 版本）
 *
 * 流程：
 * 1. setStatus PARSING
 * 2. DiffFetchService 拉取 MR Diff
 * 3. ChangeAnalyzer 分析 → ChangeUnit 列表
 * 4. 注入 commitMessage 为 prDescription
 * 5. 全部命中缓存 → 跳过 AI，直接汇总
 * 6. setStatus REVIEWING → 遍历 ChangeUnit AI 审查
 *    - 主力提供商失败 → Fallback 链降级（QWEN→DEEPSEEK→OPENAI→LOCAL）
 *    - 单元审查失败时本地快速重试 3 次（间隔 2s），全部失败记录兜底信息继续
 * 7. 汇总 Finding → 写 DB → QualityGateService → 写 gate 状态
 * 8. setStatus COMPLETED
 * 9. 异常：先写 FAILED → basicNack → cicd.dlq（独立 DLQ，不与 Webhook 共用）
 *
 * 注意：CI/CD 流水线有时间限制，不使用延迟重试队列，
 *       改为本地快速重试（最多 3 次，间隔 2s），总耗时 <10s，流水线无感知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CicdMessageConsumer {

    private final DiffFetchService diffFetchService;
    private final ChangeAnalyzer changeAnalyzer;
    private final SemanticFingerprintService fingerprintService;
    private final CicdPromptStrategy promptStrategy;
    private final ChatClientFactory chatClientFactory;
    private final CodeReviewOutputParser outputParser;
    private final ToolRegistry toolRegistry;
    private final QualityGateService qualityGateService;
    private final StringRedisTemplate redisTemplate;
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final AIConfigProperties aiConfigProperties;

    public static final String TASK_STATUS_KEY_PREFIX = "cicd:task:";
    public static final long   TASK_STATUS_TTL_SECONDS = 3600;

    /** 单元审查失败时本地重试次数上限 */
    private static final int   UNIT_RETRY_MAX     = 3;
    /** 本地重试间隔（ms） */
    private static final long  UNIT_RETRY_DELAY_MS = 2000;

    @RabbitListener(queues = RabbitMQConfig.CICD_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void consume(CicdMessage message, Channel channel, Message amqpMessage) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String taskKey = message.getTaskKey();

        log.info("[CicdConsumer] 收到消息: taskKey={}, gitUrl={}", taskKey, message.getGitUrl());

        try {
            // 1. 状态流转：PARSING
            setStatus(taskKey, "PARSING", null);

            // 2. 拉取 MR Diff
            if (message.getMrIid() == null) {
                throw new IllegalArgumentException("mrIid 为空，无法拉取 Diff");
            }
            List<FileDiff> diffs = diffFetchService.fetchMrDiffs(
                    message.getGitUrl(), message.getMrIid(), message.getCommitHash());
            if (diffs.isEmpty()) {
                log.warn("[CicdConsumer] 无变更文件，跳过审查: taskKey={}", taskKey);
                setStatus(taskKey, "COMPLETED", null);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 分析变更 → ChangeUnit 列表（commitMessage 在 analyze 阶段直接注入 RAG query）
            String prDesc = message.getCommitMessage() != null ? message.getCommitMessage() : "";
            List<ChangeUnit> units = changeAnalyzer.analyze(diffs, prDesc);
            if (units.isEmpty()) {
                log.warn("[CicdConsumer] 无有效审查单元: taskKey={}", taskKey);
                setStatus(taskKey, "COMPLETED", null);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. 注入 commitMessage（prDescription 已在 analyze 阶段透传，此处删除冗余注入）

            // 5. 全部命中缓存 → 跳过 AI
            boolean allCached = units.stream()
                    .allMatch(u -> u.getRagContext() != null && u.getRagContext().isFromCache());

            List<Finding> allFindings = new ArrayList<>();

            if (allCached) {
                log.info("[CicdConsumer] 全部命中缓存，跳过 AI 调用: taskKey={}", taskKey);
                for (ChangeUnit unit : units) {
                    if (unit.getRagContext().getCachedFindings() != null) {
                        allFindings.addAll(unit.getRagContext().getCachedFindings());
                    }
                }
            } else {
                // 6. 状态流转：REVIEWING
                setStatus(taskKey, "REVIEWING", null);

                for (ChangeUnit unit : units) {
                    List<Finding> unitFindings = reviewUnitWithRetry(unit, message.getCommitMessage(), taskKey);
                    allFindings.addAll(unitFindings);
                }
            }

            // 7. 写 DB
            Long reviewTaskId = saveToDatabase(message, allFindings);

            // 质量门禁结果写入 Redis，流水线轮询 /status 时读取
            String blockOn = message.getBlockOn() != null ? message.getBlockOn() : "HIGH";
            boolean passed = qualityGateService.checkQualityGate(allFindings, blockOn);
            String gateKey = TASK_STATUS_KEY_PREFIX + taskKey + ":gate";
            redisTemplate.opsForValue().set(gateKey, passed ? "PASSED" : "BLOCKED",
                    TASK_STATUS_TTL_SECONDS, TimeUnit.SECONDS);

            // 8. 状态流转：COMPLETED
            setStatus(taskKey, "COMPLETED", reviewTaskId);

            channel.basicAck(deliveryTag, false);
            log.info("[CicdConsumer] 消息处理完成: taskKey={}, reviewTaskId={}, findings={}, passed={}",
                    taskKey, reviewTaskId, allFindings.size(), passed);

        } catch (Exception e) {
            log.error("[CicdConsumer] 处理失败: taskKey={}, err={}", taskKey, e.getMessage(), e);
            setStatus(taskKey, "FAILED", null);
            // basicNack → cicd.dlx → cicd.dlq（独立死信，不与 Webhook 混用）
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 审查单个 ChangeUnit，含本地快速重试
     * 失败时最多重试 UNIT_RETRY_MAX 次，间隔 UNIT_RETRY_DELAY_MS ms
     * 全部失败时返回空列表（记录日志，不中断整体流程）
     */
    private List<Finding> reviewUnitWithRetry(ChangeUnit unit, String commitMessage, String taskKey) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= UNIT_RETRY_MAX; attempt++) {
            try {
                return reviewUnit(unit, commitMessage);
            } catch (Exception e) {
                lastException = e;
                log.warn("[CicdConsumer] 单元审查失败(尝试 {}/{}): taskKey={}, file={}, err={}",
                        attempt, UNIT_RETRY_MAX, taskKey, unit.getFilePath(), e.getMessage());
                if (attempt < UNIT_RETRY_MAX) {
                    try { Thread.sleep(UNIT_RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("[CicdConsumer] 单元审查重试耗尽，跳过该单元: taskKey={}, file={}", taskKey, unit.getFilePath());
        // failOnUnavailable=true：重试耗尽后向上抛，让 consume() catch 感知 → FAILED + basicNack → DLQ
        if (lastException instanceof IllegalStateException) {
            throw (IllegalStateException) lastException;
        }
        return new ArrayList<>();
    }

    /**
     * 审查单个 ChangeUnit（命中缓存直接返回，未命中调 AI）
     */
    private List<Finding> reviewUnit(ChangeUnit unit, String commitMessage) {
        if (unit.getRagContext() != null && unit.getRagContext().isFromCache()) {
            log.info("[CicdConsumer] 命中缓存: file={}", unit.getFilePath());
            return unit.getRagContext().getCachedFindings() != null
                    ? unit.getRagContext().getCachedFindings() : new ArrayList<>();
        }

        String promptText = promptStrategy.buildPrompt(unit, commitMessage);

        List<FunctionCallback> callbacks = new ArrayList<>(toolRegistry.getFunctionCallbacks());

        // 主力提供商失败后按 Fallback 顺序降级（与 Webhook 一致，无 Spring Retry）
        String response;
        try {
            response = callWithFallback(promptText, callbacks, null);
        } catch (IllegalStateException e) {
            // failOnUnavailable=true：向上穿透到 reviewUnitWithRetry，触发本地重试
            throw e;
        } catch (Exception e) {
            // failOnUnavailable=false（默认）：静默跳过该单元
            log.error("[CicdConsumer] 单元审查失败（含所有 Fallback）: file={}, err={}", unit.getFilePath(), e.getMessage());
            return new ArrayList<>();
        }

        List<Finding> findings = outputParser.parse(response);
        findings.forEach(f -> {
            if (f.getLocation() == null || f.getLocation().isBlank()) {
                f.setLocation(unit.getFilePath() + ":" + (f.getStartLine() != null ? f.getStartLine() : 0));
            }
            f.setSource("CICD-AI");
        });

        if (unit.getSemanticKey() != null) {
            fingerprintService.putToCache(unit.getSemanticKey(), findings);
        }

        log.info("[CicdConsumer] 单元审查完成: file={}, findings={}", unit.getFilePath(), findings.size());
        return findings;
    }

    /**
     * 调用 AI，主力提供商失败后按 Fallback 顺序降级（无 Spring Retry，CI/CD 有时间约束）
     * ai.fail-on-unavailable=true 时所有提供商失败抛异常（触发本地重试 reviewUnitWithRetry）
     * ai.fail-on-unavailable=false（默认）时所有提供商失败抛异常由 reviewUnit 的 catch 静默吃掉，返回空
     */
    private String callWithFallback(String promptText, List<FunctionCallback> callbacks, String preferredProvider) {
        try {
            ChatClient primary = chatClientFactory.createChatClient(preferredProvider);
            return primary.prompt(promptText)
                    .functions(callbacks.toArray(new FunctionCallback[0]))
                    .call().content();
        } catch (Exception primaryEx) {
            log.warn("[CicdConsumer] 主力提供商调用失败，尝试 Fallback: {}", primaryEx.getMessage());
        }
        try {
            ChatClient fallback = chatClientFactory.createFallbackChatClient(
                    preferredProvider != null ? preferredProvider : "QWEN");
            return fallback.prompt(promptText)
                    .functions(callbacks.toArray(new FunctionCallback[0]))
                    .call().content();
        } catch (Exception fallbackEx) {
            if (Boolean.TRUE.equals(aiConfigProperties.getFailOnUnavailable())) {
                throw new IllegalStateException("所有 AI 提供商均不可用（fail-on-unavailable=true）", fallbackEx);
            }
            throw fallbackEx;
        }
    }

    /**
     * 汇总所有 Finding，写入 DB（ReviewTask + Finding）
     */
    private Long saveToDatabase(CicdMessage message, List<Finding> allFindings) {
        ReviewTask task = ReviewTask.builder()
                .name(message.getTaskKey())
                .reviewType(ReviewTypeEnum.GIT.getValue())
                .scope(message.getGitUrl())
                .status(TaskStatusEnum.COMPLETED.getValue())
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();
        task = taskRepository.save(task);

        Long taskId = task.getId();
        for (Finding finding : allFindings) {
            finding.setTaskId(taskId);
            findingRepository.save(finding);
        }

        log.info("[CicdConsumer] 已写入 DB: taskKey={}, reviewTaskId={}, findings={}",
                message.getTaskKey(), taskId, allFindings.size());
        return taskId;
    }

    /**
     * 写入 Redis 状态机
     * cicd:task:{taskKey}:status    → PENDING/PARSING/REVIEWING/COMPLETED/FAILED/TIMEOUT
     * cicd:task:{taskKey}:reviewTaskId → reviewTaskId（完成后写入）
     * cicd:task:{taskKey}:gate       → PASSED/BLOCKED（质量门禁结果）
     * cicd:task:{taskKey}:startTime  → timestamp（Reaper 超时判断用）
     */
    public void setStatus(String taskKey, String status, Long reviewTaskId) {
        String statusKey = TASK_STATUS_KEY_PREFIX + taskKey + ":status";
        redisTemplate.opsForValue().set(statusKey, status, TASK_STATUS_TTL_SECONDS, TimeUnit.SECONDS);

        if (reviewTaskId != null) {
            String idKey = TASK_STATUS_KEY_PREFIX + taskKey + ":reviewTaskId";
            redisTemplate.opsForValue().set(idKey, String.valueOf(reviewTaskId),
                    TASK_STATUS_TTL_SECONDS, TimeUnit.SECONDS);
        }

        String startTimeKey = TASK_STATUS_KEY_PREFIX + taskKey + ":startTime";
        if (redisTemplate.opsForValue().get(startTimeKey) == null) {
            redisTemplate.opsForValue().set(startTimeKey, String.valueOf(System.currentTimeMillis()),
                    TASK_STATUS_TTL_SECONDS, TimeUnit.SECONDS);
        }

        log.debug("[CicdConsumer] 状态更新: taskKey={}, status={}", taskKey, status);
    }
}
