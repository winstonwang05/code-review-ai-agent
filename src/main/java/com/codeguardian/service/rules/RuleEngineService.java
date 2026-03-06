package com.codeguardian.service.rules;

import com.codeguardian.dto.CustomRuleDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 规范规则审查引擎
 * @author: Winston
 * @date: 2026/3/6 9:38
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final List<RuleTemplate> ruleTemplates;

    /**
     * 使用内置模板
     * @param code 需要审核的代码
     * @param language 语言
     * @param templateName 模板名字
     * @return 返回审核finding
     */
    public List<Finding> reviewWithTemplate(String code, String language, String templateName) {
        String lang = normalize(language);
        String tpl = templateName != null ? templateName.toUpperCase() : "";
        List<RuleDefinition> rules = new ArrayList<>();
        // 1.遍历每一个模板匹配找到指定模板
        boolean found = false;
        for (RuleTemplate ruleTemplate : ruleTemplates) {
            if (ruleTemplate.getName().equalsIgnoreCase(tpl) && ruleTemplate.supports(lang)) {
                // 2.获取规则系列
                rules.addAll(ruleTemplate.getRules());
                found = true;
                break;
            }
        }

        if (!found) {
            log.warn("未知或不匹配的模板: {} / {}，尝试查找通用语言支持", templateName, language);
            // 尝试只匹配语言
            for (RuleTemplate template : ruleTemplates) {
                if (template.supports(lang) && (tpl.isEmpty() || template.getName().contains(tpl))) {
                    rules.addAll(template.getRules());
                }
            }
        }
        // 3.执行审核
        return applyRules(code, rules);
    }

    /**
     * 使用自定义的自定义规则
     * @param code 需要审核的内容
     * @param customRules 自定义规则
     * @return 返回审核结果
     */
    public List<Finding> reviewWithCustom(String code, List<CustomRuleDTO> customRules) {
        List<RuleDefinition> rules = new ArrayList<>();
        // 1.构建RuleDefinition
        if (customRules != null) {
            for (CustomRuleDTO dto : customRules) {
                if (dto.getPattern() == null || dto.getPattern().isEmpty()) continue;
                rules.add(RuleDefinition.builder()
                        .name(dto.getName() != null ? dto.getName() : "自定义规则")
                        .description(dto.getPoint() != null ? dto.getPoint() : "")
                        .pattern(dto.getPattern())
                        .severity(dto.getSeverity() != null ? dto.getSeverity().toUpperCase() : "MEDIUM")
                        .weight(dto.getWeight() != null ? dto.getWeight() : 50)
                        .suggestion("建议：遵循规范，修正上述问题。")
                        .build());
            }
        }
        // 2.执行
        return applyRules(code, rules);
    }


    /**
     * 使用规则
     * @param code 需要审查的 代码
     * @param rules 规则列表
     * @return 返回审查结果
     */
    private List<Finding> applyRules(String code, List<RuleDefinition> rules) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = code != null ? code.split("\r?\n") : new String[0];
        for (RuleDefinition rule : rules) {
            try {
                // 1.遍历每一个规则并作为正则表达式的匹配模具，然后匹配代码
                Pattern pattern = Pattern.compile(rule.getPattern(), Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(code);
                while (matcher.find()) {
                    // 2.如果找到问题了，获取所在行数并设置finding属性
                    // start 就是绝对索引位置
                    int start = matcher.start();
                    int line = computeLineNumber(code, start);
                    findings.add(Finding.builder()
                            .severity(SeverityEnum.fromName(rule.getSeverity()).getValue())
                            .title(rule.getName())
                            .location("Line " + line)
                            .startLine(line)
                            // 简单起见，暂定单行
                            .endLine(line)
                            .description(rule.getDescription())
                            .suggestion(rule.getSuggestion())
                            .category("CODE_STYLE")
                            .diff(null)
                            .build());
                }
            } catch (Exception e) {
                log.warn("规则应用失败: {}", rule.getName(), e);
            }
        }
        return findings;
    }


    private String normalize(String lang) {
        if (lang == null) return "";
        String l = lang.toUpperCase();
        if (l.startsWith("JAVASCRIPT") || l.equals("JS")) return "JS";
        if (l.startsWith("TYPESCRIPT") || l.equals("TS")) return "TS";
        if (l.startsWith("JAVA")) return "JAVA";
        if (l.startsWith("PY")) return "PY";
        return l;
    }

    /**
     * 计算行号
     * @param text 当前文本（需要审查的代码）
     * @param offset 所在文本的绝对索引位置，需要获取这个绝对索引所在的行数
     * @return 返回行数
     */
    private int computeLineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if  (text.charAt(i) == '\n') line++;
        }
        return line;
    }


}
