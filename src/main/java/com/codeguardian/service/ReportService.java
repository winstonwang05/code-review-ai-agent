package com.codeguardian.service;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewReport;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewReportRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告生成服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {
    
    private final ReviewTaskRepository taskRepository;
    private final ReviewReportRepository reportRepository;
    private final FindingRepository findingRepository;
    private final CodeParserService codeParserService;
    private final SystemConfigService systemConfigService;
    private static final Pattern MD_PREFIX = Pattern.compile("^\\s*#{1,6}(?:[:\\s]*)");
    
    /**
     * 生成审查报告
     */
    public ReviewReport generateReport(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + taskId));

        if (!com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue().equals(task.getStatus())) {
            throw new IllegalStateException("任务未完成，无法生成报告: " + taskId);
        }

        ReviewReport existingReport = reportRepository.findByTaskId(taskId).orElse(null);
        if (existingReport != null) {
            log.info("报告已存在，返回现有报告: taskId={}", taskId);
            return existingReport;
        }

        List<Finding> findings = findingRepository.findByTaskId(taskId);
        int maxIssues = systemConfigService.getSettings().getMaxIssues();

        String markdownContent = generateMarkdownReport(task, findings, maxIssues);
        String htmlContent = generateHTMLReport(task, findings, maxIssues);
        String statistics = generateStatistics(findings);

        ReviewReport report = ReviewReport.builder()
                .taskId(task.getId())
                .markdownContent(markdownContent)
                .htmlContent(htmlContent)
                .statistics(statistics)
                .build();

        return reportRepository.save(report);
    }
    
    /**
     * 生成Markdown报告
     */
    private String generateMarkdownReport(ReviewTask task, List<Finding> findings, int maxIssues) {
        StringBuilder report = new StringBuilder();
        report.append("# 代码审查报告\n\n");
        report.append("**任务名称**: ").append(task.getName()).append("\n\n");
        report.append("**审查类型**: ").append(reviewTypeLabel(task.getReviewType())).append("\n\n");
        report.append("**审查范围**: ").append(reviewTypeLabel(task.getReviewType())).append("\n\n");
        String createdTime = task.getCreatedAt() != null ? TIME_FORMATTER.format(task.getCreatedAt()) : "";
        report.append("**创建时间**: ").append(createdTime).append("\n\n");
        report.append("**问题总数**: ").append(findings != null ? findings.size() : 0).append("\n\n");
        
        report.append(generateStatisticsMarkdown(findings));
        
        if (findings != null && !findings.isEmpty()) {
            report.append("## 详细问题列表\n\n");
            
            // 限制展示问题数量
            List<Finding> displayFindings = findings;
            if (maxIssues > 0 && findings.size() > maxIssues) {
                report.append("> **注意**: 仅展示前 ").append(maxIssues).append(" 个问题。\n\n");
                displayFindings = findings.stream().limit(maxIssues).collect(java.util.stream.Collectors.toList());
            }

            for (Finding finding : displayFindings) {
                report.append("### ").append(finding.getTitle()).append("\n\n");
                report.append("- **严重程度**: ").append(com.codeguardian.enums.SeverityEnum.fromValue(finding.getSeverity()).name()).append("\n");
                report.append("- **位置**: ").append(finding.getLocation()).append("\n");
                if (finding.getStartLine() != null) {
                    report.append("- **行号**: ").append(finding.getStartLine());
                    if (finding.getEndLine() != null && !finding.getEndLine().equals(finding.getStartLine())) {
                        report.append("-").append(finding.getEndLine());
                    }
                    report.append("\n");
                }
                report.append("- **类别**: ").append(finding.getCategory() != null ? finding.getCategory() : "未分类").append("\n");
                report.append("- **描述**: ").append(finding.getDescription()).append("\n");
                if (finding.getSuggestion() != null && !finding.getSuggestion().isEmpty()) {
                    report.append("- **建议**: ").append(finding.getSuggestion()).append("\n");
                }
                if (finding.getDiff() != null && !finding.getDiff().isEmpty()) {
                    report.append("- **修复建议 Diff**:\n```\n").append(finding.getDiff()).append("\n```\n");
                }
                report.append("\n");
            }
        }
        
        return report.toString();
    }
    
    /**
     * 生成HTML报告
     */
    private String generateHTMLReport(ReviewTask task, List<Finding> findings, int maxIssues) {
        int critical = countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue());
        int high = countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue());
        int medium = countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue());
        int low = countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue());

        String scopeCode = task.getScope() != null ? task.getScope() : "";
        String sampleCode = prepareSampleCode(task, scopeCode);

        StringBuilder html = new StringBuilder();
        html.append(buildHtmlHead());
        
        html.append("<div class=\"header\">\n");
        html.append("  <div class=\"title\">CodeGuardian 审查报告</div>\n");
        // 使用 JS 控制跳转到顶层窗口，避免只在 iframe 内部跳转
        html.append("  <a class=\"back\" href=\"#\" onclick=\"try{if(window.top&&window.top.history&&window.top.history.length>1){window.top.history.back();}else{window.top.location.href='/review';}}catch(e){window.location.href='/review';}return false;\"><i class=\"fas fa-arrow-left\"></i> 返回审查</a>\n");
        html.append("</div>\n");

        html.append("<div class=\"grid\">\n");
        html.append(buildOverviewSection(task, critical, high, medium, low));
        html.append(buildConfigPanel());
        html.append("</div>\n");

        html.append(buildCodePanel(sampleCode, scopeCode));
        
        // 限制展示问题数量
        List<Finding> displayFindings = findings;
        if (maxIssues > 0 && findings != null && findings.size() > maxIssues) {
            displayFindings = findings.stream().limit(maxIssues).collect(java.util.stream.Collectors.toList());
            // 可以在表格上方添加提示
        }
        
        html.append(buildFindingsTable(task, displayFindings));

        html.append("<div class=\"footer\">CodeGuardian 生成</div>\n");
        html.append("</div>\n</body>\n</html>\n");
        return html.toString();
    }

    private String prepareSampleCode(ReviewTask task, String scopeCode) {
        com.codeguardian.enums.ReviewTypeEnum rtEnum = com.codeguardian.enums.ReviewTypeEnum.fromValue(task.getReviewType());
        String rt = rtEnum.name();
        String sampleCode = scopeCode;
        try {
            if (rtEnum == com.codeguardian.enums.ReviewTypeEnum.FILE && scopeCode != null && !scopeCode.isEmpty()) {
                // 如果scopeCode看起来是代码内容（包含换行或不是路径），直接展示，不尝试读取文件
                boolean looksLikeContent = scopeCode.contains("\n") || scopeCode.contains("\r") || !scopeCode.matches(".*\\.[a-zA-Z0-9]+$");
                if (!looksLikeContent) {
                    sampleCode = codeParserService.readFile(scopeCode);
                }
            } else if (rtEnum == com.codeguardian.enums.ReviewTypeEnum.DIRECTORY && scopeCode != null && !scopeCode.isEmpty()) {
                sampleCode = codeParserService.readDirectory(scopeCode);
            } else if (rtEnum == com.codeguardian.enums.ReviewTypeEnum.PROJECT && scopeCode != null && !scopeCode.isEmpty()) {
                sampleCode = codeParserService.readProject(scopeCode);
            }
        } catch (Exception e) {
            sampleCode = scopeCode;
        }
        return stripLeadingLineNumbers(sampleCode);
    }

    private String buildHtmlHead() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\" />\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        html.append("<title>CodeGuardian 审查报告</title>\n");
        html.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css\" />\n");
        html.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css\" />\n");
        html.append("<style>");
        html.append(":root{--bg:#0d1117;--card:#161b22;--text:#c9d1d9;--text2:#8b949e;--border:#30363d;--primary:#58a6ff;--critical:#f44336;--high:#ff9800;--medium:#ffc107;--low:#4caf50;--editor-bg:#0d1117;--editor-line-number:#6e7681;--editor-text:#c9d1d9;--editor-padding:20px;}");
        html.append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif}\n");
        html.append(".wrapper{padding:24px} .header{display:flex;justify-content:space-between;align-items:center;background:var(--card);border-bottom:1px solid var(--border);padding:16px 20px;border-radius:8px} .title{font-size:18px;font-weight:600} .back{padding:8px 12px;border:1px solid var(--border);border-radius:6px;color:var(--text);background:transparent;cursor:pointer} .back:hover{background:var(--bg)}\n");
        html.append(".grid{display:grid;grid-template-columns:1fr 1.2fr;gap:12px;margin-top:12px} .panel{background:var(--card);border:1px solid var(--border);border-radius:8px;overflow:hidden;display:flex;flex-direction:column} .panel-hd{padding:14px 18px;border-bottom:1px solid var(--border);font-weight:600} .panel-bd{padding:16px 18px;flex:1} .muted{color:var(--text2)}\n");
        html.append(".overview-row{display:flex;gap:24px;flex-wrap:wrap} .overview-item{min-width:200px} .stats{display:flex;gap:12px;margin-top:12px} .stat{flex:1;background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:16px;text-align:center} .stat .num{font-size:20px;font-weight:700} .stat.critical .num{color:var(--critical)} .stat.high .num{color:var(--high)} .stat.medium .num{color:var(--medium)} .stat.low .num{color:var(--low)}\n");
        html.append(".code-block{background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:12px;overflow:auto;font-family:Monaco,Menlo,Consolas,monospace;font-size:13px;line-height:1.6} pre{margin:0;white-space:pre-wrap} \n");
        html.append(".code-editor-wrapper{flex:1;overflow:auto;position:relative;background-color:var(--editor-bg)} .code-editor-container{display:flex;position:relative;min-height:100%;align-items:flex-start} .line-numbers{padding:var(--editor-padding) 12px var(--editor-padding) var(--editor-padding);font-family:'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace;font-size:14px;line-height:1.6;color:var(--editor-line-number);background-color:var(--editor-bg);text-align:right;user-select:none;white-space:pre;border-right:1px solid var(--border);min-width:50px;box-sizing:border-box} .code-editor-pre{flex:1;margin:0;padding:0;background-color:transparent;overflow:visible;font-size:14px;line-height:1.6;font-family:'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace;box-sizing:border-box} .code-editor{display:block;width:100%;min-height:100%;padding:var(--editor-padding);font-family:'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace;font-size:14px;line-height:1.6;color:var(--editor-text);background-color:transparent;border:none;outline:none;white-space:pre;overflow-wrap:normal;overflow-x:auto;tab-size:4;margin:0;box-sizing:border-box} .code-editor:focus{outline:none} .code-editor-container pre[class*=language-]{background:transparent;margin:0;padding:0;font-size:14px;line-height:1.6;font-family:'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace;box-sizing:border-box} .code-editor-container code[class*=language-]{background:transparent;color:var(--editor-text);font-size:14px;line-height:1.6;font-family:'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace;box-sizing:border-box} .code-editor-container code[class*=language-] span,.code-editor-container code[class*=language-] .token{display:inline;font-size:inherit;line-height:inherit;font-family:inherit;vertical-align:baseline;margin:0;padding:0} .code-editor-container .token.keyword{color:#ff7b72} .code-editor-container .token.string{color:#a5d6ff} .code-editor-container .token.comment{color:#8b949e} .code-editor-container .token.function{color:#d2a8ff} .code-editor-container .token.number{color:#79c0ff}\n");
        html.append(".panel.tall .panel-bd{min-height:600px;display:flex;flex-direction:column;height:100%;padding:0 18px;overflow:hidden} .panel.tall .code-editor-wrapper{flex:1;height:100%;min-height:0;margin-top:0;align-self:stretch}\n");
        html.append(".table{margin-top:12px} .table-hd{display:grid;grid-template-columns:1.4fr 0.8fr 2fr;padding:10px 18px;color:var(--text2);border-bottom:1px solid var(--border)} .row{display:grid;grid-template-columns:1.4fr 0.8fr 2fr;gap:10px;position:relative;padding:12px 18px;border-bottom:1px solid var(--border)} .row:hover{background:#21262d} .bar{position:absolute;left:0;top:0;bottom:0;width:4px} .row.critical .bar{background:var(--critical)} .row.high .bar{background:var(--high)} .row.medium .bar{background:#e3b341} .row.low .bar{background:var(--low)}\n");
        html.append(".badge{padding:2px 8px;border-radius:10px;font-size:11px;margin-right:8px;display:inline-flex;align-items:center;gap:6px} .badge.critical{background:var(--critical);color:#fff} .badge.high{background:var(--high);color:#fff} .badge.medium{background:var(--medium);color:#fff} .badge.low{background:var(--low);color:#fff} .badge .finding-icon-shield{color:#d29922;font-size:14px} .badge .finding-icon-check{position:absolute;font-size:8px;color:#fff;top:50%;left:50%;transform:translate(-50%,-50%);z-index:1} .badge .fa-bug,.badge .fa-cog,.badge .fa-chart-bar{color:#fff;font-size:12px} .loc{font-family:Monaco,Menlo,Consolas,monospace;color:var(--text2)}\n");
        html.append(".diff{margin-top:8px;background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:10px;font-family:Monaco,Menlo,Consolas,monospace;font-size:13px} .diff-title{color:var(--text2);font-size:12px;margin-bottom:8px} .diff-line{padding:4px 8px;margin:2px 0;border-radius:4px} .removed{background:rgba(248,81,73,.15);color:var(--critical)} .added{background:rgba(63,185,80,.15);color:var(--low)}\n");
        html.append(".footer{margin-top:16px;text-align:right;color:var(--text2);font-size:12px}\n");
        html.append("</style>\n");
        html.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css\" />\n");
        html.append("<script defer=\"defer\" src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js\"></script>\n");
        html.append("<script defer=\"defer\" src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-java.min.js\"></script>\n");
        html.append("<script defer=\"defer\" src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-javascript.min.js\"></script>\n");
        html.append("<script defer=\"defer\" src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-typescript.min.js\"></script>\n");
        html.append("<script defer=\"defer\" src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-python.min.js\"></script>\n");
        html.append("</head>\n<body>\n<div class=\"wrapper\">\n");
        return html.toString();
    }

    private String buildOverviewSection(ReviewTask task, int critical, int high, int medium, int low) {
        StringBuilder html = new StringBuilder();
        html.append("  <div class=\"panel\" style=\"grid-column:1 / 3\">\n");
        html.append("    <div class=\"panel-hd\">概要</div>\n");
        html.append("    <div class=\"panel-bd\">\n");
        html.append("      <div class=\"overview-row\">\n");
        String createdAtStr = task.getCreatedAt() != null ? TIME_FORMATTER.format(task.getCreatedAt()) : "";
        html.append("        <div class=\"overview-item\"><div class=\"muted\">生成时间</div><div>").append(escapeHtml(createdAtStr)).append("</div></div>\n");
        html.append("        <div class=\"overview-item\"><div class=\"muted\">范围</div><div>").append(escapeHtml(reviewTypeLabel(task.getReviewType()))).append("</div></div>\n");
        html.append("        <div class=\"overview-item\"><div class=\"muted\">名称</div><div>").append(escapeHtml(task.getName())).append("</div></div>\n");
        html.append("      </div>\n");
        html.append("      <div class=\"stats\">\n");
        html.append("        <div class=\"stat critical\"><div class=\"muted\">严重</div><div class=\"num\">"+critical+"</div></div>\n");
        html.append("        <div class=\"stat high\"><div class=\"muted\">高危</div><div class=\"num\">"+high+"</div></div>\n");
        html.append("        <div class=\"stat medium\"><div class=\"muted\">中危</div><div class=\"num\">"+medium+"</div></div>\n");
        html.append("        <div class=\"stat low\"><div class=\"muted\">低危</div><div class=\"num\">"+low+"</div></div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        return html.toString();
    }

    private String buildConfigPanel() {
        StringBuilder html = new StringBuilder();
        html.append("  <div class=\"panel config-panel\">\n");
        html.append("    <div class=\"panel-hd\">配置与范围</div>\n");
        html.append("    <div class=\"panel-bd\">\n");
        html.append("      <div class=\"muted\">开启规则：无</div>\n");
        html.append("      <div class=\"muted\">合集标签：无</div>\n");
        html.append("      <div class=\"muted\">忽略路径：无</div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        return html.toString();
    }

    private String buildCodePanel(String sampleCode, String scopeCode) {
        StringBuilder html = new StringBuilder();
        html.append("  <div class=\"panel code-panel\">\n");
        html.append("    <div class=\"panel-hd\">代码样本</div>\n");
        html.append("    <div class=\"panel-bd\">\n");
        String normalized = normalizeLineEndings(sampleCode);
        int actualLineCount = countLines(normalized);
        StringBuilder ln = buildLineNumbers(actualLineCount);
        String lang = detectLanguage(scopeCode);
        html.append("      <div class=\"code-editor-wrapper\">\n");
        html.append("        <div class=\"code-editor-container\">\n");
        html.append("          <div class=\"line-numbers\">").append(escapeHtml(ln.toString())).append("</div>\n");
        html.append("          <pre class=\"code-editor-pre\"><code class=\"").append(lang).append(" code-editor\">").append(escapeHtml(normalized)).append("</code></pre>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        return html.toString();
    }

    private String buildFindingsTable(ReviewTask task, List<Finding> findings) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"panel table\">\n");
        html.append("  <div class=\"panel-hd\">问题详情</div>\n");
        html.append("  <div class=\"table-hd\"><div></div><div>位置</div><div>描述与建议</div></div>\n");
        if (findings == null || findings.isEmpty()) {
            html.append("  <div class=\"panel-bd\"><span class=\"muted\">暂无问题</span></div>\n");
        } else {
            for (Finding f : findings) {
                html.append(buildFindingRow(task, f));
            }
        }
        html.append("</div>\n");
        return html.toString();
    }

    private String buildFindingRow(ReviewTask task, Finding f) {
        StringBuilder html = new StringBuilder();
        com.codeguardian.enums.SeverityEnum sEnum = com.codeguardian.enums.SeverityEnum.fromValue(f.getSeverity());
        String sev = sEnum.name().toLowerCase();
        
        // 清理标题：移除Markdown标题标记（如 ####:1 -> 1）
        String title = f.getTitle() != null ? f.getTitle() : "未知问题";
        title = removeMdPrefix(title).trim();
        
        String locText = resolveDisplayName(task, f);
        
        html.append("  <div class=\"row ").append(sev).append("\">\n");
        html.append("    <div class=\"bar\"></div>\n");
        html.append("    <div><span class=\"badge ").append(sev).append("\" style=\"position:relative;\">");
        // CRITICAL类型需要特殊的图标包装器
        if (sEnum == com.codeguardian.enums.SeverityEnum.CRITICAL) {
            html.append("<span style=\"position:relative;display:inline-block;width:14px;height:14px;margin-right:4px;\">");
            html.append(badgeIcon(f.getSeverity()));
            html.append("</span>");
        } else {
            html.append(badgeIcon(f.getSeverity()));
        }
        html.append(severityLabel(f.getSeverity())).append("</span>");
        html.append(escapeHtml(title)).append("</div>\n");
        html.append("    <div class=\"loc\">").append(escapeHtml(locText)).append("</div>\n");
        
        // 描述与建议字段：按照要求的格式显示
        html.append("    <div>");
        // 描述（直接显示，不带前缀）
        if (f.getDescription() != null && !f.getDescription().isEmpty()) {
            html.append(escapeHtml(f.getDescription()));
        } else {
            html.append("无描述");
        }
        // 建议（单独一行）
        if (f.getSuggestion() != null && !f.getSuggestion().isEmpty()) {
            String suggestion = f.getSuggestion();
            // 如果suggestion已经包含"建议："前缀，则不再添加
            if (suggestion.startsWith("建议：")) {
                html.append("<br><br>").append(escapeHtml(suggestion));
            } else {
                html.append("<br><br>建议：").append(escapeHtml(suggestion));
            }
        }
        // 修复建议 Diff
        if (f.getDiff() != null && !f.getDiff().isEmpty()) {
            html.append("<br><br><div class=\"diff-title\">修复建议 Diff</div>");
            html.append("<div class=\"diff\">");
            String[] lines = f.getDiff().split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("-")) {
                    html.append("<div class=\"diff-line removed\">").append(escapeHtml(line)).append("</div>");
                } else if (trimmed.startsWith("+")) {
                    html.append("<div class=\"diff-line added\">").append(escapeHtml(line)).append("</div>");
                } else if (!trimmed.isEmpty()) {
                    html.append("<div class=\"diff-line\">").append(escapeHtml(line)).append("</div>");
                }
            }
            html.append("</div>");
        }
        html.append("    </div>\n");
        html.append("  </div>\n");
        return html.toString();
    }

    private String resolveDisplayName(ReviewTask task, Finding f) {
        String location = f.getLocation() != null ? f.getLocation() : "";
        // 规范化location，移除Markdown标题前缀（例如 ####:2 -> 2 或去掉前缀）
        if (!location.isEmpty()) {
            location = removeMdPrefix(location).trim();
        }

        // 构建位置显示文本：优先使用startLine和endLine，否则从location解析
        String displayName = "";
        String lineNumberStr = "";
        
        // 优先使用startLine和endLine
        if (f.getStartLine() != null) {
            // 从location中提取文件名
            String className = location;
            // 尝试从location中提取文件名（包含.java等扩展名）
            if (location.contains(":")) {
                String[] parts = location.split(":", -1);
                // 查找包含文件扩展名的部分
                for (String part : parts) {
                    if (part.contains(".java") || part.contains(".js") || part.contains(".ts") || 
                        part.contains(".py") || part.contains(".cpp") || part.contains(".c")) {
                        className = part.trim();
                        break;
                    }
                }
                // 如果没找到，使用第一部分
                if (className.equals(location) && parts.length > 0) {
                    className = parts[0].trim();
                }
            }
            
            // 如果location中没有文件名，尝试从task的scope中获取
            if (!className.matches(".*\\.[a-zA-Z]+.*") && task.getScope() != null) {
                String scope = task.getScope();
                // 如果是文件路径，提取文件名
                if (scope.contains("/") || scope.contains("\\")) {
                    int slashIdx = Math.max(scope.lastIndexOf('/'), scope.lastIndexOf('\\'));
                    if (slashIdx >= 0 && slashIdx + 1 < scope.length()) {
                        className = scope.substring(slashIdx + 1);
                    } else {
                        className = scope;
                    }
                } else if (scope.matches(".*\\.[a-zA-Z]+.*")) {
                    // 如果scope包含文件扩展名，使用它
                    className = scope;
                }
            }
            
            displayName = fileNameFromPath(className);

            // 再次清理可能残留的Markdown标题标记
            displayName = removeMdPrefix(displayName).trim();
            if (displayName.matches("^#+$") || displayName.isBlank()) {
                displayName = "代码片段";
            }
            
            // 如果还是没有有效的文件名（不包含文件扩展名），尝试从代码样本中推断
            if (displayName.isEmpty() || !displayName.matches(".*\\.[a-zA-Z]+.*")) {
                // 如果task的reviewType是FILE，scope应该是文件路径
                if (com.codeguardian.enums.ReviewTypeEnum.FILE.getValue().equals(task.getReviewType()) && task.getScope() != null) {
                    String scope = task.getScope();
                    displayName = fileNameFromPath(scope);
                } else {
                    // 默认使用"代码片段"或从location中提取（如果有方法名等）
                    // 尝试从location中提取类名（如果包含class关键字）
                    if (location.toLowerCase().contains("class")) {
                        // 简单提取，假设格式类似 "class UserService" 或 "UserService class"
                        String[] words = location.split("[\\s,]+");
                        for (int i = 0; i < words.length; i++) {
                            if (words[i].equalsIgnoreCase("class") && i + 1 < words.length) {
                                displayName = words[i + 1].replaceAll("[^a-zA-Z0-9_$]", "") + ".java";
                                break;
                            }
                        }
                    }
                    // 如果还是没找到，使用默认值
                    if (displayName.isEmpty() || !displayName.matches(".*\\.[a-zA-Z]+.*")) {
                        displayName = "代码片段";
                    }
                }
            }
            
            // 构建行号字符串
            if (f.getEndLine() != null && !f.getEndLine().equals(f.getStartLine())) {
                lineNumberStr = f.getStartLine() + "-" + f.getEndLine();
            } else {
                lineNumberStr = String.valueOf(f.getStartLine());
            }
        } else {
            // 从location中解析
            if (location.contains(":")) {
                String[] parts = location.split(":", 2);
                displayName = removeMdPrefix(parts[0].trim());
                lineNumberStr = parts.length > 1 ? parts[1].trim() : "";
            } else {
                displayName = location;
            }
            displayName = fileNameFromPath(displayName);
            displayName = removeMdPrefix(displayName).trim();
            if (displayName.matches("^#+$") || displayName.isBlank()) {
                displayName = "代码片段";
            }
            // 如果显示为“代码片段”或没有扩展名，且审查类型为FILE，则从scope提取文件名
            if ((displayName.isEmpty() || "代码片段".equals(displayName) || !displayName.matches(".*\\.[a-zA-Z]+.*"))
                    && com.codeguardian.enums.ReviewTypeEnum.FILE.getValue().equals(task.getReviewType()) && task.getScope() != null) {
                String scope = task.getScope();
                displayName = fileNameFromPath(scope);
                if (displayName.isEmpty() && scope != null && !scope.isEmpty()) {
                    displayName = scope;
                }
            }
        }
        
        return displayName + (lineNumberStr.isEmpty() ? "" : ":" + lineNumberStr);
    }

    private String severityLabel(Integer severity) {
        if (severity == null) return "低危";
        com.codeguardian.enums.SeverityEnum s = com.codeguardian.enums.SeverityEnum.fromValue(severity);
        return s.getDesc();
    }
    
    private String badgeIcon(Integer severity) {
        if (severity == null) return "<i class=\"fas fa-chart-bar\"></i>";
        com.codeguardian.enums.SeverityEnum s = com.codeguardian.enums.SeverityEnum.fromValue(severity);
        
        if (s == com.codeguardian.enums.SeverityEnum.CRITICAL) {
            // CRITICAL使用组合图标（盾牌+对勾）
            return "<i class=\"fas fa-shield-alt finding-icon finding-icon-shield\"></i><i class=\"fas fa-check finding-icon finding-icon-check\"></i>";
        }
        if (s == com.codeguardian.enums.SeverityEnum.HIGH) {
            return "<i class=\"fas fa-bug\"></i>";
        }
        if (s == com.codeguardian.enums.SeverityEnum.MEDIUM) {
            return "<i class=\"fas fa-cog\"></i>";
        }
        return "<i class=\"fas fa-chart-bar\"></i>";
    }
    
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
    
    private String generateStatistics(List<Finding> findings) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", findings != null ? findings.size() : 0);
        stats.put("critical", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()));
        stats.put("high", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()));
        stats.put("medium", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()));
        stats.put("low", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue()));
        return stats.toString();
    }
    
    private String generateStatisticsMarkdown(List<Finding> findings) {
        return String.format(
                "- **严重**: %d\n- **高**: %d\n- **中**: %d\n- **低**: %d\n\n",
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue())
        );
    }
    
    private String generateStatisticsHTML(List<Finding> findings) {
        return String.format(
                "<ul><li><strong>严重</strong>: %d</li><li><strong>高</strong>: %d</li><li><strong>中</strong>: %d</li><li><strong>低</strong>: %d</li></ul>\n",
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue())
        );
    }
    
    private int countBySeverity(List<Finding> findings, Integer severity) {
        if (findings == null) return 0;
        return (int) findings.stream()
                .filter(f -> severity.equals(f.getSeverity()))
                .count();
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String reviewTypeLabel(Integer type) {
        com.codeguardian.enums.ReviewTypeEnum e = com.codeguardian.enums.ReviewTypeEnum.fromValue(type);
        if (e == com.codeguardian.enums.ReviewTypeEnum.SNIPPET) return "代码片段";
        if (e == com.codeguardian.enums.ReviewTypeEnum.FILE) return "文件";
        if (e == com.codeguardian.enums.ReviewTypeEnum.DIRECTORY) return "目录";
        if (e == com.codeguardian.enums.ReviewTypeEnum.PROJECT) return "项目";
        if (e == com.codeguardian.enums.ReviewTypeEnum.GIT) return "Git";
        return "";
    }

    private String normalizeLineEndings(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private int countLines(String s) {
        String[] lines = s.split("\n", -1);
        int n = lines.length;
        return n == 0 ? 1 : n;
    }

    private StringBuilder buildLineNumbers(int count) {
        StringBuilder ln = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            ln.append(i);
            if (i < count) ln.append("\n");
        }
        return ln;
    }

    private String detectLanguage(String scopePath) {
        if (scopePath == null) return "language-java";
        String lower = scopePath.toLowerCase();
        if (lower.endsWith(".js")) return "language-javascript";
        if (lower.endsWith(".ts")) return "language-typescript";
        if (lower.endsWith(".py")) return "language-python";
        if (lower.endsWith(".java")) return "language-java";
        return "language-java";
    }

    private String stripLeadingLineNumbers(String code) {
        if (code == null) return "";
        if (!code.contains(":")) return code;
        String[] lines = code.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches("^\\d+:\\s.*")) {
                int idx = line.indexOf(':');
                String rest = idx >= 0 && idx + 1 < line.length() ? line.substring(idx + 1) : line;
                if (rest.startsWith(" ")) rest = rest.substring(1);
                out.append(rest);
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private String removeMdPrefix(String s) {
        if (s == null) return "";
        Matcher m = MD_PREFIX.matcher(s);
        return m.find() ? m.replaceFirst("") : s;
    }

    private String fileNameFromPath(String path) {
        if (path == null || path.isBlank()) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 && idx + 1 < path.length() ? path.substring(idx + 1) : path;
    }
}
