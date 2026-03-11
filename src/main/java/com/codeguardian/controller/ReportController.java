package com.codeguardian.controller;

import com.codeguardian.entity.ReviewReport;
import com.codeguardian.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告控制器
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Slf4j
public class ReportController {
    
    private final ReportService reportService;
    
    /**
     * 生成审查报告
     */
    @PostMapping("/{taskId}")
    public ResponseEntity<String> generateReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"message\":\"报告生成成功\",\"reportId\":" + report.getId() + "}");
        } catch (Exception e) {
            log.error("生成报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    /**
     * 获取HTML格式报告
     */
    @GetMapping("/{taskId}/html")
    public ResponseEntity<String> getHTMLReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                    .body(report.getHtmlContent());
        } catch (Exception e) {
            log.error("获取HTML报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 获取Markdown格式报告
     */
    @GetMapping("/{taskId}/markdown")
    public ResponseEntity<String> getMarkdownReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getMarkdownContent() == null || report.getMarkdownContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(report.getMarkdownContent());
        } catch (Exception e) {
            log.error("获取Markdown报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取PDF格式报告
     */
    @GetMapping("/{taskId}/pdf")
    public ResponseEntity<byte[]> getPdfReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // 将HTML转换为PDF
            String html = report.getHtmlContent();
            // 预处理HTML以适配PDF生成
            html = prepareHtmlForPdf(html);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
            builder.useFastMode();
            
            // 注册中文字体 - 使用更安全的方式
            boolean fontLoaded = false;
            try {
                org.springframework.core.io.Resource fontResource = new org.springframework.core.io.ClassPathResource("fonts/ArialUnicode.ttf");
                if (!fontResource.exists()) {
                    log.warn("字体文件不存在: fonts/ArialUnicode.ttf，将使用系统默认字体");
                } else {
                    java.io.File fontFile = null;
                    try {
                        // 尝试直接获取文件（开发环境）
                        fontFile = fontResource.getFile();
                        if (!fontFile.exists() || !fontFile.canRead()) {
                            throw new java.io.IOException("字体文件不可读");
                        }
                        // 验证文件大小（字体文件应该至少有几KB）
                        if (fontFile.length() < 1024) {
                            throw new java.io.IOException("字体文件大小异常: " + fontFile.length() + " bytes");
                        }
                    } catch (java.io.IOException e) {
                        // 如果在JAR中运行，无法直接获取File，需要复制到临时文件
                        try (java.io.InputStream is = fontResource.getInputStream()) {
                            if (is == null) {
                                throw new java.io.IOException("无法读取字体文件输入流");
                            }
                            fontFile = java.io.File.createTempFile("ArialUnicode", ".ttf");
                            fontFile.deleteOnExit();
                            long copied = java.nio.file.Files.copy(is, fontFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            
                            // 验证临时文件是否创建成功
                            if (!fontFile.exists() || fontFile.length() == 0 || copied == 0) {
                                throw new java.io.IOException("临时字体文件创建失败，大小: " + fontFile.length());
                            }
                            // 验证文件大小
                            if (fontFile.length() < 1024) {
                                throw new java.io.IOException("临时字体文件大小异常: " + fontFile.length() + " bytes");
                            }
                        }
                    }
                    
                    // 确保字体文件有效后再使用
                    if (fontFile != null && fontFile.exists() && fontFile.length() >= 1024) {
                        try {
                            // 验证字体文件是否是有效的TTF文件（检查文件头）
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(fontFile)) {
                                byte[] header = new byte[4];
                                if (fis.read(header) == 4) {
                                    // TTF文件应该以特定字节开头
                                    // 检查是否是有效的字体文件格式
                                    boolean isValidFont = false;
                                    // TTF文件通常以 0x00 01 00 00 或 OTTO 开头
                                    if (header[0] == 0x00 && header[1] == 0x01 && header[2] == 0x00 && header[3] == 0x00) {
                                        isValidFont = true; // TTF
                                    } else if (header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O') {
                                        isValidFont = true; // OTF
                                    } else if (header[0] == 't' && header[1] == 't' && header[2] == 'c' && header[3] == 'f') {
                                        isValidFont = true; // TTC
                                    }
                                    
                                    if (!isValidFont) {
                                        throw new java.io.IOException("字体文件格式无效，不是有效的TTF/OTF/TTC文件");
                                    }
                                }
                            }
                            
                            // 使用 useFont 注册字体
                            builder.useFont(fontFile, "ArialUnicode");
                            fontLoaded = true;
                            log.debug("成功加载中文字体: {} (大小: {} bytes)", fontFile.getAbsolutePath(), fontFile.length());
                        } catch (Exception e) {
                            log.warn("注册字体失败，将使用系统默认字体。错误: {}", e.getMessage());
                            fontLoaded = false;
                        }
                    } else {
                        log.warn("字体文件无效，将使用系统默认字体");
                    }
                }
            } catch (Exception e) {
                log.warn("无法加载中文字体，将使用系统默认字体: {}", e.getMessage());
                fontLoaded = false;
            }
            
            // 如果字体加载失败，从CSS中移除ArialUnicode引用，使用系统默认字体
            if (!fontLoaded) {
                html = html.replace("font-family:ArialUnicode,", "font-family:");
                html = html.replace("font-family:ArialUnicode;", "font-family:sans-serif;");
                // 移除所有ArialUnicode引用
                html = html.replaceAll("ArialUnicode", "sans-serif");
            }
            
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            
            try {
                builder.run();
            } catch (Exception e) {
                // 如果PDF生成失败且使用了自定义字体，尝试不使用字体重新生成
                if (fontLoaded && e.getMessage() != null && e.getMessage().contains("font")) {
                    log.warn("使用自定义字体生成PDF失败，尝试使用系统默认字体重新生成: {}", e.getMessage());
                    // 重新创建builder，不使用自定义字体
                    out.reset();
                    builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
                    builder.useFastMode();
                    html = html.replaceAll("ArialUnicode", "sans-serif");
                    builder.withHtmlContent(html, null);
                    builder.toStream(out);
                    builder.run();
                } else {
                    throw e;
                }
            }

            byte[] pdfBytes = out.toByteArray();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment()
                    .filename("review_report_" + taskId + "_" + timestamp + ".pdf")
                    .build());

            return new org.springframework.http.ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            log.error("获取PDF报告失败: taskId= {}", taskId, e);
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 预处理HTML以适配PDF生成
     * 1. 移除外部资源引用
     * 2. 展开CSS变量为实际颜色值
     * 3. 内联代码高亮样式
     * 4. 替换图标为文本
     * 5. 规范化自闭合标签
     * 6. 清理Markdown语法
     * 7. 转义未转义的&符号
     */
    private String prepareHtmlForPdf(String html) {
        // 1. 移除脚本标签
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        
        // 2. 移除外部样式表链接
        html = html.replaceAll("(?is)<link[^>]*/?>", "");
        
        // 6. 清理HTML内容中的Markdown语法（在已转义的HTML中）
        // 注意：由于内容已经被escapeHtml转义，Markdown语法会以文本形式出现
        // 我们需要在转义后的HTML中查找并清理这些Markdown标记
        html = cleanMarkdownInHtml(html);
        
        // 7. 保留HTML实体以确保代码片段中符号（如 <、>）不会被错误解析
        // openhtmltopdf 会在解析阶段处理实体，无需手动解码
        
        // 8. 转义未转义的&符号（XML要求&必须转义为&amp;）
        // 但要注意不要破坏已有的实体引用（如&amp;、&lt;、&gt;等）
        html = escapeAmpersands(html);
        
        // 3. 展开CSS变量为实际颜色值（openhtmltopdf不支持CSS变量）
        // 并添加字体定义
        html = html.replace("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif}", 
                "*{box-sizing:border-box}body{margin:0;background:#0d1117;color:#c9d1d9;font-family:ArialUnicode,sans-serif}");
        
        // 移除返回审查按钮
        html = html.replaceAll("<a class=\"back\"[^>]*>.*?</a>", "");
        
        // 移除问题详情表格中的“标题”二字
        html = html.replace("<div class=\"table-hd\"><div>标题</div>", "<div class=\"table-hd\"><div></div>");

        html = html.replaceAll("var\\(--bg\\)", "#0d1117");
        html = html.replaceAll("var\\(--card\\)", "#161b22");
        html = html.replaceAll("var\\(--text\\)", "#c9d1d9");
        html = html.replaceAll("var\\(--text2\\)", "#8b949e");
        html = html.replaceAll("var\\(--border\\)", "#30363d");
        html = html.replaceAll("var\\(--primary\\)", "#58a6ff");
        html = html.replaceAll("var\\(--critical\\)", "#f44336");
        html = html.replaceAll("var\\(--high\\)", "#ff9800");
        html = html.replaceAll("var\\(--medium\\)", "#ffc107");
        html = html.replaceAll("var\\(--low\\)", "#4caf50");
        html = html.replaceAll("var\\(--editor-bg\\)", "#0d1117");
        html = html.replaceAll("var\\(--editor-line-number\\)", "#6e7681");
        html = html.replaceAll("var\\(--editor-text\\)", "#c9d1d9");
        html = html.replaceAll("var\\(--editor-padding\\)", "20px");
        
        // 4. 内联Prism代码高亮样式（tomorrow主题）以及PDF专用布局优化
        String prismStyles = "<style>\n" +
            "/* Prism Tomorrow Theme for PDF */\n" +
            "code[class*=\"language-\"], pre[class*=\"language-\"] {\n" +
            "  color: #c9d1d9;\n" +
            "  background: #0d1117;\n" +
            "  text-shadow: none;\n" +
            "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
            "  font-size: 12px;\n" +
            "  line-height: 1.6;\n" +
            "}\n" +
            "/* 修复PDF中代码编辑器样式对齐问题 - 使用table布局确保兼容性 */\n" +
            ".code-editor-container {\n" +
            "  display: table;\n" +
            "  width: 100%;\n" +
            "  table-layout: fixed;\n" +
            "  background: #0d1117;\n" +
            "  border-collapse: collapse;\n" +
            "}\n" +
            ".line-numbers {\n" +
            "  display: table-cell;\n" +
            "  vertical-align: top;\n" +
            "  width: 50px;\n" +
            "  padding: 0 8px 0 0;\n" +
            "  text-align: right;\n" +
            "  border-right: 1px solid #30363d;\n" +
            "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
            "  font-size: 12px;\n" +
            "  line-height: 19.2px;\n" +
            "  color: #6e7681;\n" +
            "  background-color: #0d1117;\n" +
            "  white-space: pre;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            ".code-editor-pre {\n" +
            "  display: table-cell;\n" +
            "  vertical-align: top;\n" +
            "  margin: 0;\n" +
            "  padding: 0;\n" +
            "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
            "  font-size: 12px;\n" +
            "  line-height: 19.2px;\n" +
            "  background-color: transparent;\n" +
            "  white-space: pre;\n" +
            "  overflow-x: auto;\n" +
            "  overflow-y: hidden;\n" +
            "  box-sizing: border-box;\n" +
            "  width: auto;\n" +
            "  max-width: 100%;\n" +
            "}\n" +
            ".code-editor-pre code {\n" +
            "  display: block;\n" +
            "  margin: 0;\n" +
            "  padding: 0;\n" +
            "  font-size: 12px;\n" +
            "  line-height: 19.2px;\n" +
            "  font-family: inherit;\n" +
            "  color: #c9d1d9;\n" +
            "  background: transparent;\n" +
            "}\n" +
            ".code-editor-pre code span,\n" +
            ".code-editor-pre code .token {\n" +
            "  display: inline;\n" +
            "  font-size: 12px;\n" +
            "  line-height: 19.2px;\n" +
            "  vertical-align: baseline;\n" +
            "  margin: 0;\n" +
            "  padding: 0;\n" +
            "}\n" +
            "/* 确保代码编辑器包装器可见且不跨页，添加滚动条 */\n" +
            ".code-editor-wrapper {\n" +
            "  display: block;\n" +
            "  width: 100%;\n" +
            "  overflow-x: auto;\n" +
            "  overflow-y: visible;\n" +
            "  background: #0d1117;\n" +
            "  page-break-inside: avoid;\n" +
            "  break-inside: avoid;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            "/* 代码编辑器容器添加滚动支持 - 确保超出页面宽度时显示滚动条 */\n" +
            ".code-editor-container {\n" +
            "  width: 100%;\n" +
            "  max-width: 100%;\n" +
            "  overflow-x: auto;\n" +
            "  overflow-y: visible;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            "/* 确保代码编辑器面板有滚动条 */\n" +
            ".panel.code-panel .code-editor-wrapper {\n" +
            "  width: 100%;\n" +
            "  max-width: 100%;\n" +
            "  overflow-x: auto;\n" +
            "  overflow-y: visible;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            ".panel.code-panel .code-editor-container {\n" +
            "  width: 100%;\n" +
            "  max-width: 100%;\n" +
            "  overflow-x: auto;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            ".panel.code-panel .code-editor-pre {\n" +
            "  max-width: 100%;\n" +
            "  overflow-x: auto;\n" +
            "  box-sizing: border-box;\n" +
            "}\n" +
            "/* 配置与范围面板整体样式优化（使用 config-panel 类） */\n" +
            ".config-panel {\n" +
            "  page-break-inside: avoid;\n" +
            "  break-inside: avoid;\n" +
            "  margin-bottom: 12px;\n" +
            "}\n" +
            ".config-panel .panel-bd {\n" +
            "  max-height: 40px;\n" +
            "  overflow: hidden;\n" +
            "  padding: 4px 12px;\n" +
            "  flex: 0 0 auto;\n" +
            "}\n" +
            ".config-panel .panel-hd {\n" +
            "  padding: 6px 18px;\n" +
            "  font-size: 13px;\n" +
            "}\n" +
            ".config-panel .panel-bd .muted {\n" +
            "  font-size: 10px;\n" +
            "  line-height: 1.1;\n" +
            "  margin: 0;\n" +
            "  padding: 0;\n" +
            "}\n" +
            "/* 确保配置与范围在第一页，代码样本在第二页 */\n" +
            ".panel.code-panel {\n" +
            "  page-break-before: always;\n" +
            "  break-before: page;\n" +
            "}\n" +
            ".panel.table {\n" +
            "  page-break-before: always;\n" +
            "  break-before: page;\n" +
            "}\n" +
            ".panel.code-panel .panel-bd {\n" +
            "  min-height: 400px;\n" +
            "  max-height: 500px;\n" +
            "  overflow-y: auto;\n" +
            "}\n" +
            "/* 代码编辑器包装器分页控制 */\n" +
            ".code-editor-wrapper {\n" +
            "  display: block;\n" +
            "  width: 100%;\n" +
            "  overflow: visible;\n" +
            "  background: #0d1117;\n" +
            "  page-break-inside: avoid;\n" +
            "  break-inside: avoid;\n" +
            "}\n" +
            /* 缩短配置与范围模块高度并避免跨页 */
            ".config-panel { page-break-inside: avoid; -fs-page-break-inside: avoid; page-break-after: avoid; -fs-page-break-after: avoid; display: inline-block; width: 100%; overflow: visible; }\n" +
            ".config-panel .panel-bd { min-height: initial; max-height: 40px; overflow: hidden; padding: 4px 12px; flex: 0 0 auto; }\n" +
            ".config-panel .panel-hd { padding: 8px 18px; }\n" +
            ".config-panel .panel-bd .muted { font-size: 11px; line-height: 1.2; }\n" +
            ".config-panel, .config-panel .panel-hd, .config-panel .panel-bd { page-break-inside: avoid; -fs-page-break-inside: avoid; }\n" +
            /* 压缩顶部概要与网格间距，提升首页容纳内容 */
            ".grid { gap: 8px; margin-top: 8px; }\n" +
            ".overview-row { gap: 12px; }\n" +
            ".stats { gap: 8px; }\n" +
            ".token.comment, .token.prolog, .token.doctype, .token.cdata {\n" +
            "  color: #8b949e;\n" +
            "}\n" +
            ".token.punctuation {\n" +
            "  color: #c9d1d9;\n" +
            "}\n" +
            ".token.property, .token.tag, .token.boolean, .token.number, .token.constant, .token.symbol, .token.deleted {\n" +
            "  color: #79c0ff;\n" +
            "}\n" +
            ".token.selector, .token.attr-name, .token.string, .token.char, .token.builtin, .token.inserted {\n" +
            "  color: #a5d6ff;\n" +
            "}\n" +
            ".token.operator, .token.entity, .token.url, .language-css .token.string, .style .token.string {\n" +
            "  color: #ff7b72;\n" +
            "}\n" +
            ".token.atrule, .token.attr-value, .token.keyword {\n" +
            "  color: #ff7b72;\n" +
            "}\n" +
            ".token.function, .token.class-name {\n" +
            "  color: #d2a8ff;\n" +
            "}\n" +
            ".token.regex, .token.important, .token.variable {\n" +
            "  color: #ffa657;\n" +
            "}\n" +
            "</style>\n";
        
        // 在</style>标签后插入Prism样式
        html = html.replace("</style>", "</style>\n" + prismStyles);
        
        // 5. 替换Font Awesome图标为Unicode字符或文本
        // 先处理CRITICAL类型的组合图标（包含span包装的盾牌+对勾）
        html = html.replaceAll("(?is)<span[^>]*position:relative[^>]*>.*?<i class=\"fas fa-shield-alt[^\"]*\"></i>.*?<i class=\"fas fa-check[^\"]*\"></i>.*?</span>", "🛡");
        // 然后处理单个图标
        html = html.replaceAll("(?i)<i class=\"fas fa-arrow-left\"></i>", "←");
        html = html.replaceAll("(?i)<i class=\"fas fa-shield-alt[^\"]*\"></i>", "🛡");
        html = html.replaceAll("(?i)<i class=\"fas fa-check[^\"]*\"></i>", "✓");
        html = html.replaceAll("(?i)<i class=\"fas fa-bug\"></i>", "🐛");
        html = html.replaceAll("(?i)<i class=\"fas fa-cog\"></i>", "⚙");
        html = html.replaceAll("(?i)<i class=\"fas fa-chart-bar\"></i>", "📊");
        // 移除所有剩余的Font Awesome图标标签
        html = html.replaceAll("(?i)<i class=\"fas[^\"]*\"></i>", "");
        
        // 6. 规范化自闭合标签
        html = html.replaceAll("(?i)<(meta|img|br|hr|input|area|base|col|embed|source|track|wbr)([^>]*?)(?<!\\s/)(?<!/)>", "<$1$2 />");
        
        return html;
    }
    
    /**
     * 清理HTML内容中的Markdown语法
     * 由于内容已经被escapeHtml转义，Markdown语法会以文本形式出现在HTML中
     * 我们需要清理这些Markdown标记，使其在PDF中正常显示
     */
    private String cleanMarkdownInHtml(String html) {
        // 使用正则表达式匹配HTML标签之间的文本内容，并清理Markdown标记
        // 模式：匹配 >文本内容< 中的文本内容部分
        
        // 多次清理，确保所有Markdown标记都被移除
        for (int i = 0; i < 5; i++) {
            html = html.replaceAll("(>)(\\s*)#{1,6}(?:[:\\s]*)", "$1$2");
            
            // 清理Markdown粗体标记（**text**）
            html = html.replaceAll("(>)([^<]*?)\\*\\*([^*]+?)\\*\\*([^<]*?)(<)", "$1$2$3$4$5");
            
            // 清理Markdown代码标记（`code`）
            html = html.replaceAll("(>)([^<]*?)`([^`]+?)`([^<]*?)(<)", "$1$2$3$4$5");
            
            // 清理Markdown链接标记（[text](url)）
            html = html.replaceAll("(>)([^<]*?)\\[([^\\]]+?)\\]\\([^\\)]+\\)([^<]*?)(<)", "$1$2$3$4$5");
            
            // 清理Markdown列表标记（-、*、+ 后跟空格）
            html = html.replaceAll("(>)([^<]*?)([-*+])(\\s+)([^<]*?)(<)", "$1$2$5$6");
            
            // 清理Markdown引用标记（> 后跟空格）
            html = html.replaceAll("(>)([^<]*?)(>)(\\s+)([^<]*?)(<)", "$1$2$5$6");
            
            // 清理可能残留的#号（单独出现的#号，不在行首）
            html = html.replaceAll("(>)([^<]*?)(#{1,6})([^<]*?)(<)", "$1$2$4$5");
        }
        
        // 特别处理：清理紧跟在标签后的Markdown标题语法（如 <div>####:1</div> -> <div>1</div>）
        // 匹配：标签结束符 > 后紧跟 #号、冒号或空格
        html = html.replaceAll("(>)\\s*#+[:\\s]*", "$1");
        
        // 专门针对 </span> 后面的标题格式进行清理 (针对 badge 后的标题)
        html = html.replaceAll("(</span>\\s*)#+[:\\s]*", "$1");
        
        // 最后，更激进地清理所有独立的#号
        // 清理所有单独的#号（包括单个和多个）
            html = html.replaceAll("(>)([^<]*?)(#{1,})([^<]*?)(<)", "$1$2$4$5");
        
        // 清理可能出现在文本中间的#号（前后都有内容的情况）
        html = html.replaceAll("(>)([^<]*?)(#{1,})([^<]*?)(<)", "$1$2$4$5");
        
        // 再次清理，确保没有遗漏
        html = html.replaceAll("(>)([^<]*?)(#{1,})([^<]*?)(<)", "$1$2$4$5");
        
        return html;
    }
    
    /**
     * 转义未转义的&符号
     * XML要求&必须转义为&amp;，但要注意不要破坏已有的实体引用
     */
    private String escapeAmpersands(String html) {
        // 使用正则表达式匹配未转义的&符号
        // 匹配：& 后面不是实体引用的情况
        // 实体引用模式：&[a-zA-Z]+; 或 &#[0-9]+; 或 &#x[0-9a-fA-F]+;
        
        // 先保护已有的实体引用（将它们替换为占位符）
        java.util.Map<String, String> entityMap = new java.util.HashMap<>();
        int placeholderIndex = 0;
        
        // 匹配所有实体引用
        java.util.regex.Pattern entityPattern = java.util.regex.Pattern.compile("&(?:[a-zA-Z]+|#[0-9]+|#x[0-9a-fA-F]+);");
        java.util.regex.Matcher entityMatcher = entityPattern.matcher(html);
        StringBuffer protectedHtml = new StringBuffer();
        
        while (entityMatcher.find()) {
            String entity = entityMatcher.group();
            String placeholder = "___ENTITY_" + placeholderIndex++ + "___";
            entityMap.put(placeholder, entity);
            entityMatcher.appendReplacement(protectedHtml, java.util.regex.Matcher.quoteReplacement(placeholder));
        }
        entityMatcher.appendTail(protectedHtml);
        
        // 转义所有剩余的&符号
        String escapedHtml = protectedHtml.toString().replace("&", "&amp;");
        
        // 恢复实体引用
        for (java.util.Map.Entry<String, String> entry : entityMap.entrySet()) {
            escapedHtml = escapedHtml.replace(entry.getKey(), entry.getValue());
        }
        
        return escapedHtml;
    }
    
    /**
     * 将HTML实体转换为实际字符
     * 确保在PDF中正确显示，而不是显示实体代码
     * 注意：只处理内容区域的实体，不处理HTML标签属性中的实体
     */
    private String decodeHtmlEntities(String html) {
        // 常见的HTML实体映射（命名实体）
        java.util.Map<String, String> entityMap = new java.util.HashMap<>();
        entityMap.put("&lt;", "<");
        entityMap.put("&gt;", ">");
        entityMap.put("&quot;", "\"");
        entityMap.put("&#39;", "'");
        entityMap.put("&apos;", "'");
        entityMap.put("&nbsp;", " ");
        entityMap.put("&#160;", " ");
        
        // 注意：不处理 &amp;，因为我们需要保留它用于后续的&符号转义
        
        // 先处理命名实体（除了&amp;）
        for (java.util.Map.Entry<String, String> entry : entityMap.entrySet()) {
            html = html.replace(entry.getKey(), entry.getValue());
        }
        
        // 处理数字实体（&#123; 格式）
        // 只处理内容区域，不处理HTML标签属性
        java.util.regex.Pattern decimalPattern = java.util.regex.Pattern.compile("&#(\\d+);");
        java.util.regex.Matcher decimalMatcher = decimalPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (decimalMatcher.find()) {
            try {
                int code = Integer.parseInt(decimalMatcher.group(1));
                if (code >= 0 && code <= 0xFFFF) {
                    char ch = (char) code;
                    decimalMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(ch)));
                }
            } catch (Exception e) {
                // 如果转换失败，保持原样
            }
        }
        decimalMatcher.appendTail(sb);
        html = sb.toString();
        
        // 处理十六进制实体（&#x1F; 格式）
        java.util.regex.Pattern hexPattern = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);");
        java.util.regex.Matcher hexMatcher = hexPattern.matcher(html);
        sb = new StringBuffer();
        while (hexMatcher.find()) {
            try {
                int code = Integer.parseInt(hexMatcher.group(1), 16);
                if (code >= 0 && code <= 0xFFFF) {
                    char ch = (char) code;
                    hexMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(ch)));
                }
            } catch (Exception e) {
                // 如果转换失败，保持原样
            }
        }
        hexMatcher.appendTail(sb);
        html = sb.toString();
        
        return html;
    }
}
