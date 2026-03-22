package com.codeguardian.controller;

import com.codeguardian.mq.WebhookMessage;
import com.codeguardian.mq.WebhookMessageProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * @description: Webhook 控制层
 * 职责：极速接收 → Redis Lua 防抖 → 投递 MQ，不做任何业务处理
 * 业务逻辑全部移至 WebhookMessageConsumer
 * @author: Winston
 * @date: 2026/3/12 19:36
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookMessageProducer producer;

    /**
     * 处理 GitCode (GitLab compatible) Webhook
     * 关注事件: Merge Request Hook
     */
    @PostMapping("/gitcode")
    public ResponseEntity<String> handleGitCodeWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {

        log.info("收到 GitCode Webhook 事件: {}", eventType);

        // 1. 过滤非 MR 事件
        String objectKind = (String) payload.get("object_kind");
        if (!"merge_request".equals(objectKind)) {
            return ResponseEntity.ok("忽略的事件类型: " + objectKind);
        }

        Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
        if (attributes == null) {
            return ResponseEntity.badRequest().body("无效的请求体: 缺少 object_attributes");
        }

        // 2. 过滤非目标操作
        String action = (String) attributes.get("action");
        if (!"open".equals(action) && !"update".equals(action) && !"reopen".equals(action)) {
            return ResponseEntity.ok("忽略的操作: " + action);
        }

        // 3. 提取关键字段
        Map<String, Object> project = (Map<String, Object>) payload.get("project");
        if (project == null) {
            return ResponseEntity.badRequest().body("无效的请求体: 缺少 project");
        }

        String gitUrl = (String) project.get("git_http_url");
        Integer mrIid = (Integer) attributes.get("iid");
        String mrTitle = (String) attributes.get("title");
        String prDescription = (String) attributes.getOrDefault("description", "");

        Map<String, Object> lastCommit = (Map<String, Object>) attributes.get("last_commit");
        String commitHash = lastCommit != null ? (String) lastCommit.get("id") : null;

        // commit 时间戳：优先用 committer date，保证防抖顺序正确；没有则用当前时间
        Long commitTimestamp = System.currentTimeMillis();
        if (lastCommit != null && lastCommit.get("timestamp") instanceof String ts) {
            try {
                commitTimestamp = java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // 4. 构建消息，mrKey = "项目路径:mrIid"，唯一标识一个 PR
        String mrKey = extractProjectPath(gitUrl) + ":" + mrIid;
        WebhookMessage message = WebhookMessage.builder()
                .mrKey(mrKey)
                .gitUrl(gitUrl)
                .commitHash(commitHash)
                .commitTimestamp(commitTimestamp)
                .mrIid(mrIid)
                .mrTitle(mrTitle != null ? mrTitle : "")
                .prDescription(prDescription != null ? prDescription : "")
                .build();

        // 5. 防抖 + 投递 MQ（立即返回，不阻塞）
        boolean sent = producer.sendWithDebounce(message);
        String resp = sent ? "Webhook 已接收并投递处理队列。" : "Webhook 已接收，commit 已被更新的提交覆盖，本次忽略。";
        log.info("[WebhookController] mrKey={}, commit={}, sent={}", mrKey, commitHash, sent);

        return ResponseEntity.ok(resp);
    }

    private String extractProjectPath(String gitUrl) {
        if (gitUrl == null) return "unknown";
        String clean = gitUrl.replace(".git", "");
        if (clean.startsWith("http")) {
            int idx = clean.indexOf("/", clean.indexOf("://") + 3);
            if (idx != -1) return clean.substring(idx + 1);
        }
        return clean;
    }
}
