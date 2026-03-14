package com.codeguardian.service;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.config.ChatClientFactory;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.service.ai.PromptService;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.service.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: AI服务类
 * 使用Spring AI的ChatClient和PromptTemplate进行代码审查
 * @author: Winston
 * @date: 2026/3/9 9:08
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIModelService {

    private final ChatClientFactory chatClientFactory;
    private final PromptService promptService;
    private final CodeReviewOutputParser outputParser;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AIConfigProperties aiConfigProperties;
    private final ToolRegistry toolRegistry;

    /**
     * 审查代码
     *
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param modelProvider 模型提供商（可选，如果为null则使用配置的provider）
     * @param enableRag 是否启用RAG知识库增强
     * @return 审查发现的问题列表
     */
    public List<Finding> reviewCode(String codeContent, String language, String modelProvider, boolean enableRag) {
        return reviewCode(codeContent, language, modelProvider, enableRag, null);
    }


    /**
     * 审查代码（带已有发现--静态分析结果）
     *
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param modelProvider 模型提供商
     * @param enableRag 是否启用RAG
     * @param existingFindings 已有的静态分析发现
     * @return 审查发现的问题列表
     */
    // 针对异常重试3次，每一次的间隔2秒，之后每次间隔时间乘1.5倍
    @Retryable(
            retryFor = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 1.5)
    )
    public List<Finding> reviewCode(String codeContent, String language, String modelProvider, boolean enableRag, List<Finding> existingFindings) {
        // 1.检查是否需要调用大模型
        if (Boolean.FALSE.equals(aiConfigProperties.getEnabled())) {
            log.warn("LLM调用已禁用（ai.enabled=false），跳过大模型审查");
            return new ArrayList<>();
        }

        String finalProvider = modelProvider != null ? modelProvider : "默认";
        log.info("========== 开始AI代码审查 ==========");
        log.info("语言: {}, 代码长度: {} 字符, 模型提供商: {}, RAG增强: {}, 已有发现数: {}",
                language, codeContent.length(), finalProvider, enableRag, existingFindings != null ? existingFindings.size() : 0);
        try {
            // 清理上下文中的旧数据
            ReviewContextHolder.clear();

            // 2.获取上下文（RAG增强）
            // RAG Retrieval (知识库增强)
            String context = null;
            if (enableRag) {
                context = retrieveContext(codeContent, language);
            }

            // 3.构建Prompt
            long promptStartTime = System.currentTimeMillis();
            Prompt prompt = promptService.buildCodeReviewPrompt(codeContent, language, context, existingFindings);
            long promptBuildTime = System.currentTimeMillis() - promptStartTime;

            // 获取提示词内容
            String promptText = prompt.getContents();
            log.info("Prompt构建完成, 耗时: {}ms, Prompt长度: {} 字符",
                    promptBuildTime, promptText.length());

            // 打印完整的提示词内容
            log.info("========== 发送给大模型的提示词 ==========");
            log.info("{}", promptText);
            log.info("========== 提示词结束 ==========");

            // 4.获取工具的回调函数，Spring AI底层会回调函数
            ArrayList<FunctionCallback> functionCallbacks = new ArrayList<>(toolRegistry.getFunctionCallbacks());

            // 5.判断静态资源是否提前调用，调用了需要临时将Semgrep的回调函数删除
            if (existingFindings != null && !existingFindings.isEmpty()) {
                functionCallbacks.removeIf(
                        functionCallback -> functionCallback
                                .getName()
                                .equalsIgnoreCase("semgrepAnalysis"));
            }
            if (!functionCallbacks.isEmpty()) {
                log.info("启用工具: {}", functionCallbacks.stream().map(FunctionCallback::getName).toList());
            } else {
                log.warn("未发现任何注册工具，将仅进行纯文本审查");
            }


            // 6.使用Spring AI的ChatClient处理,底层SpringAI控制反转回调
            // 调用AI API（使用Spring AI的ChatClient）
            long apiStartTime = System.currentTimeMillis();
            log.info("[Step 1] 发送请求给大模型 (等待模型响应或Function Calling请求)...");

            ChatClient chatClient = chatClientFactory.createChatClient(modelProvider);
            String response = chatClient.prompt(prompt)
                    .functions(functionCallbacks.toArray(new FunctionCallback[0]))
                    .call()
                    .content();

            log.info("[Step Final] 大模型最终响应完成");
            long apiCallTime = System.currentTimeMillis() - apiStartTime;

            log.info("AI API调用成功: 响应时间={}ms, 响应内容长度={} 字符",
                    apiCallTime, response != null ? response.length() : 0);

            // 打印AI返回的原始内容（用于调试）
            if (response != null) {
                log.info("========== AI返回的原始内容 ==========");
                log.info("{}", response);
                log.info("========== AI返回内容结束 ==========");
            } else {
                log.warn("AI返回的内容为空");
            }

            // 7.解析结果将与Semgrep合并
            long parseStartTime = System.currentTimeMillis();
            List<Finding> findings = outputParser.parse(response);
            long parseTime = System.currentTimeMillis() - parseStartTime;
            // 从上下文中获取
            List<Finding> toolFindings = ReviewContextHolder.getFindings();
            if (toolFindings != null && !toolFindings.isEmpty()) {
                for (Finding finding : toolFindings) {
                    boolean exists = findings.stream().anyMatch(f ->
                        (f.getStartLine() != null && f.getStartLine().equals(finding.getStartLine())) &&
                                (f.getTitle() != null && f.getTitle().contains(finding.getTitle()))
                    );

                    if  (!exists) {
                        findings.add(finding);
                    }
                }


            }


            log.info("========== AI审查完成 ==========");
            log.info("提供商: {}, 发现问题数: {}, 总耗时: {}ms, 解析耗时: {}ms",
                    finalProvider,
                    findings.size(),
                    System.currentTimeMillis() - promptStartTime,
                    parseTime);

            if (findings.isEmpty()) {
                log.warn("未发现任何问题，可能需要检查Prompt或模型响应");
            } else {
                // 统计问题严重程度
                long criticalCount = findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count();
                long highCount = findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count();
                long mediumCount = findings.stream().filter(f -> "MEDIUM".equals(f.getSeverity())).count();
                long lowCount = findings.stream().filter(f -> "LOW".equals(f.getSeverity())).count();
                log.info("问题统计: CRITICAL={}, HIGH={}, MEDIUM={}, LOW={}",
                        criticalCount, highCount, mediumCount, lowCount);
            }

            return findings;
        } catch (Exception e) {
            log.error("========== AI审查异常 ==========");
            log.error("提供商: {}, 错误信息: {}", finalProvider, e.getMessage(), e);
            throw e; // 必须向上抛出，触发 @Retryable
        } finally {
            ReviewContextHolder.clear();
            log.debug("当前线程的上下文已清理完毕");
        }
    }


    /**
     * 达到最大重试次数后的恢复方法
     * 兜底方案：通过动态注册的工具获取静态分析结果
     */
    @Recover
    public List<Finding> recover(Exception e, String codeContent, String language, String modelProvider, boolean enableRag, List<Finding> existingFindings) {
        log.warn("========== AI API调用重试3次失败，启动兜底方案 ==========");
        log.warn("错误信息: {}", e.getMessage());
        log.info("兜底方案: 使用动态注册的静态分析工具进行代码审查");

        List<Finding> fallbackFindings = new ArrayList<>();

        // 1. 如果已有静态分析结果，直接返回
        if (existingFindings != null && !existingFindings.isEmpty()) {
            log.info("使用已有的静态分析结果作为兜底方案，问题数: {}", existingFindings.size());
            return existingFindings;
        }

        // 2. 通过ToolRegistry调用Semgrep静态分析工具
        if (toolRegistry.getToolNames().contains("semgrepAnalysis")) {
            try {
                log.info("通过动态注册的ToolRegistry调用Semgrep静态分析工具");
                com.codeguardian.service.ai.tools.SemgrepAnalyzerTool.Request semgrepRequest =
                    new com.codeguardian.service.ai.tools.SemgrepAnalyzerTool.Request();
                semgrepRequest.code = codeContent;

                Object result = toolRegistry.execute("semgrepAnalysis",
                    "{\"code\":\"" + codeContent.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");

                if (result instanceof com.codeguardian.service.ai.tools.SemgrepAnalyzerTool.Response semgrepResponse) {
                    if (semgrepResponse.getFindings() != null && !semgrepResponse.getFindings().isEmpty()) {
                        fallbackFindings.addAll(semgrepResponse.getFindings());
                        log.info("Semgrep静态分析完成，发现问题数: {}", semgrepResponse.getFindings().size());
                    }
                }
            } catch (Exception toolException) {
                log.error("调用Semgrep工具失败", toolException);
            }
        } else {
            log.warn("ToolRegistry中未找到Semgrep工具，跳过静态分析");
        }

        // 3. 如果Semgrep也没有发现问题，生成一个兜底的提示
        if (fallbackFindings.isEmpty()) {
            log.warn("静态分析未发现问题，生成兜底提示");
            Finding fallbackFinding = Finding.builder()
                    .title("AI审查失败-静态分析兜底")
                    .description("AI大模型调用重试3次后失败，静态分析工具也未发现问题。建议：1. 检查网络连接和API配置 2. 代码可能较为简单，无明显问题")
                    .severity(SeverityEnum.LOW.getValue())
                    .category("MAINTAINABILITY")
                    .startLine(1)
                    .endLine(1)
                    .location("Code Snippet:1")
                    .suggestion("建议：手动检查代码逻辑，或修复API配置后重新审查")
                    .source("Fallback")
                    .build();
            fallbackFindings.add(fallbackFinding);
        }

        log.info("========== 兜底方案执行完成 ==========");
        log.info("提供商: {}, 问题数: {}", modelProvider != null ? modelProvider : "默认", fallbackFindings.size());
        return fallbackFindings;
    }

    /**
     * 检索知识库上下文
     */
    private String retrieveContext(String code, String language) {
        try {
            // 1.构造查询：使用语言和代码片段的前500个字符 (减小查询长度，避免噪音)
            if (knowledgeBaseService == null) return null;

            String query = "Language: " + language + "\nCode Snippet: " +
                    code.substring(0, Math.min(code.length(), 500));

            // 2.使用 searchSnippets 获取切片，而不是完整文档
            List<String> snippets = knowledgeBaseService.searchSnippets(query, 3);
            if (snippets.isEmpty()) return null;

            // 3.将切片结果添加行号
            StringBuilder sb = new StringBuilder();
            sb.append("相关代码规范与最佳实践：\n");
            for (int i = 0; i < snippets.size(); i++) {
                sb.append(i + 1).append(". ").append(snippets.get(i)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            return null;
        }
    }
}
