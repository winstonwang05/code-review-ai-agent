package com.codeguardian.service;

import com.codeguardian.entity.SystemConfig;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 全局设置服务
 * @author: Winston
 * @date: 2026/3/9 15:52
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigRepository repository;


    // Keys
    public static final String KEY_PROJECT_ROOT = "general.project_root";
    public static final String KEY_RULE_STANDARD = "rules.standard";
    public static final String KEY_RULE_PRESET = "rules.preset";
    public static final String KEY_SCOPE_INCLUDE = "scope.include";
    public static final String KEY_SCOPE_EXCLUDE = "scope.exclude";
    public static final String KEY_VIS_CHART_HEIGHT = "visualization.chart.height";
    public static final String KEY_VIS_RING_THICKNESS = "visualization.ring.thickness";
    public static final String KEY_BEHAVIOR_MAX_ISSUES = "behavior.max_issues";
    public static final String KEY_BEHAVIOR_ANIMATION = "behavior.animation_interval";
    public static final String KEY_METRICS_DASHBOARD = "metrics.dashboard";

    public static final String PREFIX_RULE_CAT = "rules.category.";
    public static final String PREFIX_RULE_WEIGHT = "rules.weight.";

    private static final String[] CATEGORIES = {"security", "performance", "maintainability", "style", "logic_error"};

    /**
     * 获取所有设置信息
     * @return 返回DTO
     */
    public SettingsDTO getSettings() {
        // 1.从数据库中查询所有的配置
        List<SystemConfig> systemConfigs = repository.findAll();

        // 2.将所有配置key - value放置在Map集合中，查询效率提高
        Map<String, String> configMap = new HashMap<String, String>();
        systemConfigs.forEach(config -> configMap.put(config.getConfigKey(), config.getConfigValue()));

        // 3.封装集合返回
        SettingsDTO dto = new SettingsDTO();
        dto.setProjectRoot(configMap.getOrDefault(KEY_PROJECT_ROOT, ""));
        dto.setRuleStandard(configMap.getOrDefault(KEY_RULE_STANDARD, "alibaba"));
        dto.setRulePreset(configMap.getOrDefault(KEY_RULE_PRESET, "general"));
        dto.setIncludePaths(configMap.getOrDefault(KEY_SCOPE_INCLUDE, "src/main/java"));
        dto.setExcludePaths(configMap.getOrDefault(KEY_SCOPE_EXCLUDE, "target\n.git\ntest"));

        dto.setChartHeight(parseInt(configMap.get(KEY_VIS_CHART_HEIGHT), 300));
        dto.setRingThickness(parseInt(configMap.get(KEY_VIS_RING_THICKNESS), 20));
        dto.setMaxIssues(parseInt(configMap.get(KEY_BEHAVIOR_MAX_ISSUES), 100));
        dto.setAnimationInterval(parseInt(configMap.get(KEY_BEHAVIOR_ANIMATION), 600));

        Map<String, Boolean> ruleCategories = new HashMap<>();
        Map<String, Integer> ruleWeights = new HashMap<>();

        for (String category : CATEGORIES) {
            ruleCategories.put(category, parseBoolean(configMap.get(PREFIX_RULE_CAT + category), true));
            ruleWeights.put(category, parseInt(configMap.get(PREFIX_RULE_WEIGHT + category), 20));
        }
        dto.setRuleCategories(ruleCategories);
        dto.setRuleWeights(ruleWeights);

        return dto;
    }
    /**
     * 获取指定配置项的原始字符串值
     *
     * @param key 配置项键
     * @return 配置项值（若不存在返回 null）
     */
    public String getConfigValue(String key) {
        return repository.findByConfigKey(key).map(SystemConfig::getConfigKey).orElse(null);
    }


    /**
     * 保存原始的配置键值对
     *
     * <p>适用于非结构化配置，如缓存的统计 JSON 等。</p>
     *
     * @param key 配置项键
     * @param value 配置项值
     * @param category 配置分类
     * @param description 配置描述
     */
    public void saveRawConfig(String key, String value, String category, String description) {
        if (value == null) value = "";
        SystemConfig config = repository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).build());
        config.setConfigValue(value);
        config.setCategory(category);
        config.setDescription(description);
        repository.save(config);
    }


    @Transactional
    public void saveSettings(SettingsDTO dto) {
        validateSettings(dto);

        // 一条一条插入数据库中
        log.info("Saving system settings: {}", dto);

        saveConfig(KEY_PROJECT_ROOT, dto.getProjectRoot(), "General", "Project Root Directory");
        saveConfig(KEY_RULE_STANDARD, dto.getRuleStandard(), "Rules", "Code Standard");
        saveConfig(KEY_RULE_PRESET, dto.getRulePreset(), "Rules", "Rule Preset");
        saveConfig(KEY_SCOPE_INCLUDE, dto.getIncludePaths(), "Scope", "Included Paths");
        saveConfig(KEY_SCOPE_EXCLUDE, dto.getExcludePaths(), "Scope", "Excluded Paths");

        saveConfig(KEY_VIS_CHART_HEIGHT, String.valueOf(dto.getChartHeight()), "Visualization", "Chart Height");
        saveConfig(KEY_VIS_RING_THICKNESS, String.valueOf(dto.getRingThickness()), "Visualization", "Ring Thickness");

        saveConfig(KEY_BEHAVIOR_MAX_ISSUES, String.valueOf(dto.getMaxIssues()), "Behavior", "Max Issues");
        saveConfig(KEY_BEHAVIOR_ANIMATION, String.valueOf(dto.getAnimationInterval()), "Behavior", "Animation Interval");

        if  (dto.getRuleCategories() != null) {
            dto.getRuleCategories().forEach((category, enabled) ->
                    saveConfig(category + PREFIX_RULE_WEIGHT, String.valueOf(enabled), "Rules", "Category Enabled: " + category));
        }

        if  (dto.getRuleWeights() != null) {
            dto.getRuleWeights().forEach((category, weight) -> saveConfig(
                    PREFIX_RULE_WEIGHT + category, String.valueOf(weight), "Rules", "Category Weight: " + category)
            );
        }

    }

    private void saveConfig(String key, String value, String category, String description) {
        if (value == null) value = "";
        SystemConfig config = repository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).build());
        config.setConfigValue(value);
        config.setCategory(category);
        config.setDescription(description);
        repository.save(config);
    }


    private void validateSettings(SettingsDTO dto) {
        if (dto.getChartHeight() != null && dto.getChartHeight() < 100) {
            throw new IllegalArgumentException("可视化图表高度必须至少为 100px");
        }
        if (dto.getRingThickness() != null && dto.getRingThickness() < 5) {
            throw new IllegalArgumentException("可视化圆环厚度必须至少为 5px");
        }
        if (dto.getMaxIssues() != null && dto.getMaxIssues() < 1) {
            throw new IllegalArgumentException("最大问题数必须至少为 1");
        }
        if (dto.getAnimationInterval() != null && dto.getAnimationInterval() < 0) {
            throw new IllegalArgumentException("动画间隔不能为负数");
        }
        if (dto.getRuleWeights() != null) {
            for (Integer weight : dto.getRuleWeights().values()) {
                if (weight != null && (weight < 0 || weight > 100)) {
                    throw new IllegalArgumentException("规则权重必须在 0 到 100 之间");
                }
            }
        }
    }


    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        return Boolean.parseBoolean(value);
    }
}
