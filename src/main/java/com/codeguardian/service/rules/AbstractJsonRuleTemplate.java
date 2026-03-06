package com.codeguardian.service.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;


import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * @description: 基于JSON文件的规则模板抽象基类
 * @author: Winston
 * @date: 2026/3/6 9:23
 * @version: 1.0
 */
@Slf4j
public abstract class AbstractJsonRuleTemplate implements RuleTemplate{

    @Autowired
    private ObjectMapper objectMapper;
    private JsonTemplateConfig jsonTemplateConfig;

    /**
     * 子类提供JSON文件路径（相对于classpath）
     */
    protected abstract String getJsonFilePath();

    /**
     * 将每一个Json文件下的内容转化为实体类
     * 每当继承类Bean对象创建好之后就会执行
     */
    @PostConstruct
    public void init(){
        if (objectMapper == null){
            objectMapper = new ObjectMapper();
        }
        // 1.获取具体策略中Json文件路径
        String path = getJsonFilePath();
        try {
            ClassPathResource classPathResource = new ClassPathResource(path);
            if (!classPathResource.exists()) {
                log.error("Rule template file not found: {}", path);
                this.jsonTemplateConfig = new JsonTemplateConfig();
                this.jsonTemplateConfig.setName("ERROR");
                this.jsonTemplateConfig.setRules(Collections.emptyList());
                return;
            }
            // 2.通过输入流获取数据
            try (InputStream inputStream = classPathResource.getInputStream()) {
                // 3.将数据内容转化为Java对象
                this.jsonTemplateConfig = objectMapper.readValue(inputStream, JsonTemplateConfig.class);
                log.info("Successfully loaded {} rules from template: {}",
                        jsonTemplateConfig.getRules() != null ? jsonTemplateConfig.getRules().size() : 0, path);
            }

        } catch (IOException e) {
            log.error("Failed to load rules from {}", path, e);
            this.jsonTemplateConfig = new JsonTemplateConfig();
            this.jsonTemplateConfig.setName("ERROR");
            this.jsonTemplateConfig.setRules(Collections.emptyList());
        }

    }

    @Override
    public String getDescription() {
        return jsonTemplateConfig != null ? jsonTemplateConfig.getDescription() : "";
    }

    @Override
    public String getName() {
        return jsonTemplateConfig != null ? jsonTemplateConfig.getName() : "";
    }

    @Override
    public List<RuleDefinition> getRules() {
        return jsonTemplateConfig != null && jsonTemplateConfig.getRules() != null
                ? jsonTemplateConfig.getRules()
                : Collections.emptyList();
    }


    // Json文件下对应的实体类
    @Data
    public static class JsonTemplateConfig {
        private String name;
        private String description;
        private List<RuleDefinition> rules;
    }

}
