package com.codeguardian.mq;

import com.codeguardian.config.RabbitMQConfig;
import com.codeguardian.config.RetryQueueConfig;
import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.config.ChatClientFactory;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.diff.ChangeAnalyzer;
import com.codeguardian.service.diff.DiffFetchService;
import com.codeguardian.service.diff.SemanticFingerprintService;
import com.codeguardian.service.diff.model.ChangeType;
import com.codeguardian.service.diff.model.ChangeUnit;
import com.codeguardian.service.diff.model.FileDiff;
import com.codeguardian.service.diff.prompt.DeletePromptStrategy;
import com.codeguardian.service.diff.prompt.WebhookPromptStrategy;
import com.codeguardian.service.integration.GitFeedbackService;
import com.codeguardian.service.integration.QualityGateService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Webhook MQ 消费者（Diff-based 版本）
 *
 * 流程：
 * 1. 惰性丢弃（Redis 比对最新 commitHash）
 * 2. updateStatus → pending
 * 3. DiffFetchService 拉取 MR Diff
 * 4. ChangeAnalyzer 分析 → ChangeUnit 列表
 * 5. 注入 prDescription 到所有 ChangeUnit
 * 6. 遍历 ChangeUnit：命中缓存直接计数；未命中则 AI 审查
 *    - DELETE 场景使用 DeletePromptStrategy
 *    - ADD/MODIFY 使用 WebhookPromptStrategy
 * 7. done==total → SETNX 锁 → 幂等 hasComment → QualityGateService → 写 MR 评论 + 更新 commit 状态
 * 8. 异常：retryCount<3 → 重试队列(5min)；>=3 → DLQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookMessageConsumer {

    private final DiffFetchService diffFetchService;
    private final ChangeAnalyzer changeAnalyzer;
    private final SemanticFingerprintService fingerprintService;
    private final WebhookPromptStrategy promptStrategy;
    private final DeletePromptStrategy deletePromptStrategy;
    private final ChatClientFactory chatClientFactory;
    private final CodeReviewOutputParser outputParser;
    private final ToolRegistry toolRegistry;
    private final GitFeedbackService gitFeedbackService;
    private final QualityGateService qualityGateService;
    private final ReviewTaskRepository reviewTaskRepository;
    private final FindingRepository findingRepository;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AIConfigProperties aiConfigProperties;

    private static final String DEBOUNCE_KEY_PREFIX   = "webhook:debounce:pr:";
    private static final String META_KEY_PREFIX       = "webhook:pr:";
    private static final String AGGREGATE_LOCK_PREFIX = "webhook:aggregating:pr:";
    private static final int    META_TTL_MINUTES      = 30;
    private static final long   LOCK_TTL_SECONDS      = 120;

    @RabbitListener(queues = RabbitMQConfig.WEBHOOK_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void consume(WebhookMessage message, Channel channel, Message amqpMessage) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String mrKey = message.getMrKey();

        log.info("[WebhookConsumer] 收到消息: mrKey={}, commit={}", mrKey, message.getCommitHash());

        try {
            // 1. 惰性丢弃
            String latestHash = (String) redisTemplate.opsForHash()
                    .get(DEBOUNCE_KEY_PREFIX + mrKey, "hash");
            if (latestHash != null && !latestHash.equals(message.getCommitHash())) {
                log.info("[WebhookConsumer] 惰性丢弃: mrKey={}, 当前={}, 最新={}",
                        mrKey, message.getCommitHash(), latestHash);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 更新 commit 状态为 pending
            if (message.getCommitHash() != null) {
                gitFeedbackService.updateStatus(message.getGitUrl(), message.getCommitHash(),
                        "pending", "CodeGuardian AI 审查中...");
            }

            // 3. 拉取 MR Diff
            if (message.getMrIid() == null) {
                throw new IllegalArgumentException("mrIid 为空，无法拉取 Diff");
            }
            List<FileDiff> diffs = diffFetchService.fetchMrDiffs(
                    message.getGitUrl(), message.getMrIid(), message.getCommitHash());
            if (diffs.isEmpty()) {
                log.warn("[WebhookConsumer] MR#{} 无变更文件，跳过审查", message.getMrIid());
                gitFeedbackService.updateStatus(message.getGitUrl(), message.getCommitHash(),
                        "success", "无代码变更，跳过审查");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. 分析变更 → ChangeUnit 列表（prDescription 在 analyze 阶段直接注入 RAG query）
            String prDesc = message.getPrDescription() != null && !message.getPrDescription().isBlank()
                    ? message.getPrDescription() : message.getCommitMessage();
            List<ChangeUnit> units = changeAnalyzer.analyze(diffs, prDesc);
            if (units.isEmpty()) {
                log.warn("[WebhookConsumer] 无有效审查单元，跳过");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 5. 写 Redis 账本
            String metaKey = META_KEY_PREFIX + mrKey + ":meta";
            redisTemplate.opsForHash().put(metaKey, "total", String.valueOf(units.size()));
            redisTemplate.opsForHash().put(metaKey, "done", "0");
            redisTemplate.opsForHash().put(metaKey, "gitUrl", message.getGitUrl());
            redisTemplate.opsForHash().put(metaKey, "mrIid", String.valueOf(message.getMrIid()));
            redisTemplate.opsForHash().put(metaKey, "commitHash",
                    message.getCommitHash() != null ? message.getCommitHash() : "");
            redisTemplate.opsForHash().put(metaKey, "startTime", String.valueOf(System.currentTimeMillis()));
            redisTemplate.expire(metaKey, META_TTL_MINUTES, TimeUnit.MINUTES);

            // 6. 遍历审查
            List<Finding> allFindings = new ArrayList<>();
            for (ChangeUnit unit : units) {
                List<Finding> unitFindings = reviewUnit(unit, message.getCommitMessage());
                allFindings.addAll(unitFindings);

                Long done  = redisTemplate.opsForHash().increment(metaKey, "done", 1);
                Long total = Long.parseLong((String) redisTemplate.opsForHash().get(metaKey, "total"));
                log.debug("[WebhookConsumer] 进度: mrKey={}, done={}/{}", mrKey, done, total);

                if (done != null && done.equals(total)) {
                    aggregateAndPostResult(message, allFindings);
                }
            }

            channel.basicAck(deliveryTag, false);
            log.info("[WebhookConsumer] 消息处理完成: mrKey={}, findings={}", mrKey, allFindings.size());

        } catch (Exception e) {
            log.error("[WebhookConsumer] 处理失败: mrKey={}, err={}", mrKey, e.getMessage(), e);
            handleFailure(message, channel, deliveryTag, e);
        }
    }

    /**
     * 审查单个 ChangeUnit
     * - 命中语义指纹缓存 → 直接返回
     * - DELETE 场景 → DeletePromptStrategy
     * - ADD/MODIFY  → WebhookPromptStrategy
     */
    private List<Finding> reviewUnit(ChangeUnit unit, String commitMessage) {
        if (unit.getRagContext() != null && unit.getRagContext().isFromCache()) {
            log.info("[WebhookConsumer] 命中语义指纹缓存: file={}, method={}",
                    unit.getFilePath(), unit.getMethod() != null ? unit.getMethod().getMethodName() : "N/A");
            return unit.getRagContext().getCachedFindings() != null
                    ? unit.getRagContext().getCachedFindings() : new ArrayList<>();
        }

        try {
            // 根据变更类型选择 Prompt 策略
            boolean isDelete = unit.getChangeType() == ChangeType.FULL_DELETE
                    || unit.getChangeType() == ChangeType.PARTIAL_DELETE;
            String promptText = isDelete
                    ? deletePromptStrategy.buildPrompt(unit, commitMessage)
                    : promptStrategy.buildPrompt(unit, commitMessage);

            List<FunctionCallback> callbacks = new ArrayList<>(toolRegistry.getFunctionCallbacks());
            String response = callWithFallback(promptText, callbacks, null);

            List<Finding> findings = outputParser.parse(response);
            findings.forEach(f -> {
                if (f.getLocation() == null || f.getLocation().isBlank()) {
                    f.setLocation(unit.getFilePath() + ":" + (f.getStartLine() != null ? f.getStartLine() : 0));
                }
                f.setSource("Webhook-AI");
            });

            // 写语义指纹缓存（仅 METHOD_LEVEL ADD/MODIFY 场景）
            if (unit.getSemanticKey() != null && !isDelete) {
                fingerprintService.putToCache(unit.getSemanticKey(), findings);
            }

            log.info("[WebhookConsumer] 单元审查完成: file={}, findings={}", unit.getFilePath(), findings.size());
            return findings;

        } catch (IllegalStateException e) {
            // failOnUnavailable=true：所有提供商不可用，向上穿透到 consume() catch → 重试队列
            throw e;
        } catch (Exception e) {
            log.error("[WebhookConsumer] 单元审查失败（含所有 Fallback）: file={}, err={}", unit.getFilePath(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 调用 AI，主力提供商失败后按 Fallback 顺序降级尝试
     * ai.fail-on-unavailable=true 时所有提供商失败抛异常（触发重试队列）
     * ai.fail-on-unavailable=false（默认）时所有提供商失败抛异常由 reviewUnit 的 catch 静默吃掉，返回空
     */
    private String callWithFallback(String promptText, List<FunctionCallback> callbacks, String preferredProvider) {
        // 先尝试主力提供商
        try {
            ChatClient primary = chatClientFactory.createChatClient(preferredProvider);
            return primary.prompt(promptText)
                    .functions(callbacks.toArray(new FunctionCallback[0]))
                    .call().content();
        } catch (Exception primaryEx) {
            log.warn("[WebhookConsumer] 主力提供商调用失败，尝试 Fallback: {}", primaryEx.getMessage());
        }

        // 主力失败 → 按 Fallback 顺序降级
        try {
            ChatClient fallback = chatClientFactory.createFallbackChatClient(
                    preferredProvider != null ? preferredProvider : "QWEN");
            return fallback.prompt(promptText)
                    .functions(callbacks.toArray(new FunctionCallback[0]))
                    .call().content();
        } catch (Exception fallbackEx) {
            // 所有提供商均不可用
            if (Boolean.TRUE.equals(aiConfigProperties.getFailOnUnavailable())) {
                // 安全优先：向上抛出，触发消费者端重试队列
                throw new IllegalStateException("所有 AI 提供商均不可用（fail-on-unavailable=true）", fallbackEx);
            }
            // 高可用优先（默认）：向上抛出，由 reviewUnit 的 catch 静默返回空
            throw fallbackEx;
        }
    }

    /**
     * 汇总所有 Finding，加锁防并发，幂等检查防重复评论
     * 质量门禁由 QualityGateService 决定，不再硬编码 severity
     */
    private void aggregateAndPostResult(WebhookMessage message, List<Finding> allFindings) {
        String lockKey = AGGREGATE_LOCK_PREFIX + message.getMrKey() + ":" + message.getCommitHash();

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("[WebhookConsumer] 未获得汇总锁，跳过: mrKey={}", message.getMrKey());
            return;
        }

        try {
            if (gitFeedbackService.hasComment(message.getGitUrl(),
                    String.valueOf(message.getMrIid()), "CodeGuardian AI 审查报告")) {
                log.info("[WebhookConsumer] 评论已存在，跳过重复写入: mrKey={}", message.getMrKey());
                return;
            }

            // 持久化：写 ReviewTask + Finding（与 CI/CD 保持一致，触发方式不影响存库）
            ReviewTask task = ReviewTask.builder()
                    .name(message.getMrTitle() != null && !message.getMrTitle().isBlank()
                            ? message.getMrTitle() : message.getMrKey())
                    .reviewType(ReviewTypeEnum.GIT.getValue())
                    .scope(message.getGitUrl())
                    .status(TaskStatusEnum.COMPLETED.getValue())
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();
            task = reviewTaskRepository.save(task);
            final Long taskId = task.getId();
            allFindings.forEach(f -> f.setTaskId(taskId));
            findingRepository.saveAll(allFindings);
            log.info("[WebhookConsumer] 持久化完成: mrKey={}, reviewTaskId={}, findings={}",
                    message.getMrKey(), taskId, allFindings.size());

            // 质量门禁（默认 HIGH，即存在 CRITICAL/HIGH 则不通过）
            boolean passed = qualityGateService.checkQualityGate(allFindings, "HIGH");

            String report = buildMarkdownReport(allFindings, passed);
            gitFeedbackService.postComment(message.getGitUrl(), String.valueOf(message.getMrIid()), report);

            gitFeedbackService.updateStatus(message.getGitUrl(), message.getCommitHash(),
                    passed ? "success" : "failed",
                    passed ? "审查通过，共发现 " + allFindings.size() + " 个问题"
                           : "审查未通过，存在 CRITICAL/HIGH 级别问题");

            log.info("[WebhookConsumer] 汇总完成: mrKey={}, findings={}, passed={}",
                    message.getMrKey(), allFindings.size(), passed);
        } catch (Exception e) {
            log.error("[WebhookConsumer] 汇总回写失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 异常处理：retryCount < 3 → 重试队列；>= 3 → DLQ
     */
    private void handleFailure(WebhookMessage message, Channel channel, long deliveryTag, Exception e) throws Exception {
        try {
            String errMsg = e.getMessage() != null && e.getMessage().length() > 100
                    ? e.getMessage().substring(0, 100) + "..." : e.getMessage();
            gitFeedbackService.updateStatus(message.getGitUrl(), message.getCommitHash(),
                    "failed", "AI 审查异常: " + errMsg);
        } catch (Exception ignored) {}

        if (message.getRetryCount() < 3) {
            message.setRetryCount(message.getRetryCount() + 1);
            rabbitTemplate.convertAndSend(
                    RetryQueueConfig.WEBHOOK_RETRY_EXCHANGE,
                    RetryQueueConfig.WEBHOOK_RETRY_KEY,
                    message);
            log.warn("[WebhookConsumer] 投入重试队列: mrKey={}, retryCount={}",
                    message.getMrKey(), message.getRetryCount());
            channel.basicAck(deliveryTag, false);
        } else {
            log.error("[WebhookConsumer] 重试耗尽，进入死信队列: mrKey={}", message.getMrKey());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 构建 Markdown 格式审查报告
     */
    private String buildMarkdownReport(List<Finding> findings, boolean passed) {
        long critical = findings.stream().filter(f -> f.getSeverity() != null && f.getSeverity() == 0).count();
        long high     = findings.stream().filter(f -> f.getSeverity() != null && f.getSeverity() == 1).count();
        long medium   = findings.stream().filter(f -> f.getSeverity() != null && f.getSeverity() == 2).count();
        long low      = findings.stream().filter(f -> f.getSeverity() != null && f.getSeverity() == 3).count();

        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 CodeGuardian AI 审查报告\n\n");
        sb.append("### 问题统计\n\n");
        sb.append("| 级别 | 数量 |\n|------|------|\n");
        sb.append("| 🔴 CRITICAL | ").append(critical).append(" |\n");
        sb.append("| 🟠 HIGH     | ").append(high).append(" |\n");
        sb.append("| 🟡 MEDIUM   | ").append(medium).append(" |\n");
        sb.append("| 🟢 LOW      | ").append(low).append(" |\n");
        sb.append("| **合计**    | **").append(findings.size()).append("** |\n\n");
        sb.append(passed
                ? "✅ **审查结论：通过**（无 CRITICAL/HIGH 级别问题）\n"
                : "❌ **审查结论：未通过**（存在 CRITICAL 或 HIGH 级别问题，请修复后重新提交）\n");

        findings.stream()
                .filter(f -> f.getSeverity() != null && f.getSeverity() <= 1)
                .limit(10)
                .forEach(f -> sb.append("\n---\n")
                        .append("**").append(f.getTitle()).append("**")
                        .append(" `").append(f.getLocation()).append("`\n")
                        .append(f.getDescription()).append("\n")
                        .append(f.getSuggestion() != null ? "> " + f.getSuggestion() + "\n" : ""));

        return sb.toString();
    }
}
