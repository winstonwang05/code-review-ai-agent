package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.GitFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @description: Webhook 控制层
 * 处理 GitHub/GitLab 的 Webhook（回调） 事件
 * @author: Winston
 * @date: 2026/3/12 19:36
 * @version: 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {


    private final ReviewService reviewService;
    private final GitFeedbackService gitFeedbackService;


    /**
     * 处理 GitCode (GitLab compatible) Webhook
     * 关注事件: Merge Request Hook
     */
    @PostMapping("/gitcode")
    public ResponseEntity<String> handleGitCodeWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {

        // 1.过滤掉不是MR（Merge Request）的内容
        log.info("收到 GitCode Webhook 事件: {}", eventType);

        String objectKind = (String) payload.get("object_kind");
        if (!"merge_request".equals(objectKind)) {
            return ResponseEntity.ok("忽略的事件类型: " + objectKind);
        }

        Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
        if (attributes == null) {
            return ResponseEntity.badRequest().body("无效的请求体: 缺少 object_attributes");
        }
        // 2.过滤掉不是更新，打开，重新打开MQ的内容
        String action = (String) attributes.get("action");
        if (!"open".equals(action) && !"update".equals(action) && !"reopen".equals(action)) {
            return ResponseEntity.ok("忽略的操作: " + action);
        }

        // 3.异步处理
        CompletableFuture.runAsync(() -> processGitCodeMr(payload));

        return ResponseEntity.ok("Webhook 已接收并开始处理。");
    }

    /**
     * 异步处理回调的代码审查和状态更新
     * @param payload 请求体，包括gitUrl等
     */
    private void processGitCodeMr(Map<String, Object> payload) {
        // 1.需要声明gitUrl和commit的哈希为全局变量，保证catch块能够得到变量（降级策略）
        String gitUrl = null;
        String commitHash = null;

        try {
            // 2.获取请求体中的数据
            Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
            Map<String, Object> project = (Map<String, Object>) payload.get("project");

            if (attributes == null || project == null) return;

            gitUrl = (String) project.get("git_http_url");
            String htmlUrl = (String) project.get("web_url");
            String branch = (String) attributes.get("source_branch");

            Map<String, Object> lastCommit = (Map<String, Object>) attributes.get("last_commit");
            commitHash = lastCommit != null ? (String) lastCommit.get("id") : null;

            Integer mrIid = (Integer) attributes.get("iid");
            String mrTitle = (String) attributes.get("title");

            log.info("正在处理 GitCode MR: {}/merge_requests/{} (branch: {}, commit: {})", htmlUrl, mrIid, branch, commitHash);

            // 3.更新状态
            if (commitHash != null) {
                gitFeedbackService.updateStatus(gitUrl, commitHash, "pending", "CodeGuardian AI 审查中...");
            }

            ReviewRequestDTO reviewRequestDTO = ReviewRequestDTO.builder()
                    .gitUrl(gitUrl)
                    .reviewType("GIT")
                    .taskName("MR-" + mrIid + "-" + mrTitle)
                    .build();

            // 4.执行审查任务
            ReviewResponseDTO reviewTask = reviewService.createReviewTask(reviewRequestDTO);

            // 5.发送评论
            gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid),
                    "🤖 **CodeGuardian AI** 已开始审查此 MR。\n\n" +
                            "任务 ID: `" + reviewTask.getTaskId() + "`\n" +
                            "请等待后续审查报告。");
        } catch (Exception e) {
            log.error("处理 GitCode Webhook 失败: {}", e.getMessage(), e);

            String errorMsg = "AI 审查服务内部异常: " + e.getMessage();
            // 截断错误信息，防止太长导致 API 报错（通常描述有长度限制）
            if (errorMsg.length() > 100) errorMsg = errorMsg.substring(0, 100) + "...";

            // 如果gitUrl和commitHash不为空，直接更新状态为失败
            gitFeedbackService.updateStatus(gitUrl, commitHash, "failed", errorMsg);

        }
    }
}
