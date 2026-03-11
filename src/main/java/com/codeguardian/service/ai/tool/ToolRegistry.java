package com.codeguardian.service.ai.tool;

import com.codeguardian.service.ai.dto.FunctionDefinition;
import com.codeguardian.service.ai.dto.ToolDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * @description: 工具实例一起存入本地 Map 路由表
 * Spring AI底层会把 Map 里的工具全部打包成 Spring AI 标准格式，和提示词（Prompt）、用户源码一起，化作一个巨大的 HTTP JSON 报文发给大模型
 * @author: Winston
 * @date: 2026/3/8 16:34
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistry {


    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    /**
     * 存储在本地内存的Map集合，里面是每一个工具
     */
    private final Map<String, ToolWrapper> tools = new ConcurrentHashMap<>();

    /**
     * 初始化
     */
    @PostConstruct
    public void init() {
        // 扫描所有 Function 类型的 Bean
        String[] beanNames = applicationContext.getBeanNamesForType(Function.class);

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);

            Description description = applicationContext.findAnnotationOnBean(beanName, Description.class);
            if (description != null) {
                registerFunctionBean(beanName, (Function<?, ?>) bean, description.value());
            }
        }
    }


    /**
     * 将工具放在本地Map集合中
     * @param name 工具bean的name
     * @param function 工具bean对象
     * @param description 工具的描述
     */
    private void registerFunctionBean(String name, Function<?, ?> function, String description) {
        try {
            // 1.由于泛型擦除，工具处理的Request模板被擦除，所以需要获取
            Class<?> inputType = Object.class;

            // 1.1从工具注册的bean的name尝试获取
            if (name.equals("javaSyntaxAnalysis")) {
                inputType = com.codeguardian.service.ai.tools.JavaSyntaxAnalyzerTool.Request.class;
            } else if (name.equals("semgrepAnalysis")) {
                inputType = com.codeguardian.service.ai.tools.SemgrepAnalyzerTool.Request.class;
            } else {
                // 1.2还是不行，直接通过反射机制获取工具的实现的接口获取参数（Request模板）
                try {
                    // 获取工具实现所有接口
                    Type[] genericInterfaces = function.getClass().getGenericInterfaces();
                    for (Type type : genericInterfaces) {
                        // 找到实现Function接口
                        if (type instanceof ParameterizedType) {
                            ParameterizedType pt = (ParameterizedType) type;
                            if (pt.getRawType().equals(Function.class)) {
                                // 获取Function接口的第一个参数也就是Request
                                Type[] args = pt.getActualTypeArguments();
                                if (args.length > 0 && args[0] instanceof Class) {
                                    inputType = (Class<?>) args[0];
                                    break;
                                }
                            }
                        }
                    }
                    if (inputType == Object.class) {
                        log.warn("未知函数 Bean {}, 跳过 Schema 生成", name);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("无法推断 {} 的输入类型, 跳过", name);
                    return;
                }
            }


            // 2.构建Json Schema给AI看
            Map<String, Object> parameters = generateJsonSchema(inputType);

            // 3.封装到本地Map集合
            ToolDefinition toolDefinition = ToolDefinition.builder()
                    .type("function")
                    .function(FunctionDefinition.builder()
                            .name(name)
                            .description(description)
                            .parameters(parameters)
                            .build())
                    .build();
            tools.put(name, new ToolWrapper(toolDefinition, function, inputType));
            log.info("已注册工具: {}", name);
        } catch (Exception e) {
            log.error("注册工具 {} 失败", name, e);
        }
    }

    /**
     * 构建Json Schema
     */
    private Map<String, Object> generateJsonSchema(Class<?> clazz) {
        try {
            // 使用 Jackson 的简化 JSON Schema 生成器
            // 注意：理想情况下应使用 jackson-module-jsonSchema 或类似库
            // 这里我们手动为 Record 类构建一个简单的 Schema

            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");

            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();

            for (Field field : clazz.getDeclaredFields()) {
                Map<String, Object> prop = new HashMap<>();
                prop.put("type", "string"); // 默认为 String 类型

                if (field.getType().equals(int.class) || field.getType().equals(Integer.class)) {
                    prop.put("type", "integer");
                } else if (field.getType().equals(boolean.class) || field.getType().equals(Boolean.class)) {
                    prop.put("type", "boolean");
                }

                JsonPropertyDescription desc = field.getAnnotation(JsonPropertyDescription.class);
                if (desc != null) {
                    prop.put("description", desc.value());
                }

                JsonProperty jsonProp =
                        field.getAnnotation(JsonProperty.class);
                if (jsonProp != null && jsonProp.required()) {
                    required.add(field.getName());
                }

                // 添加到属性列表
                properties.put(field.getName(), prop);
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }

            return schema;
        } catch (Exception e) {
            log.error("为 {} 生成 Schema 失败", clazz, e);
            return Map.of();
        }
    }

    public List<ToolDefinition> getTools() {
        return tools.values().stream()
                .map(ToolWrapper::getDefinition)
                .collect(java.util.stream.Collectors.toList());
    }

    public java.util.Set<String> getToolNames() {
        return tools.keySet();
    }


    /**
     * 遍历本地tools的工具并实现FunctionCallback接口，SpringAI底层会封装所有内容，包括调用重写方法
     * @return 返回打包后的FunctionCallback集合
     */
    public List<FunctionCallback> getFunctionCallbacks() {
        return tools.values().stream()
                .map(toolWrapper -> new FunctionCallback() {
                    @Override
                    public String getName() {
                        return toolWrapper.getDefinition().getFunction().getName();
                    }

                    @Override
                    public String getDescription() {
                        return toolWrapper.getDefinition().getFunction().getDescription();
                    }

                    @Override
                    public String getInputTypeSchema() {
                        try {
                            return objectMapper.writeValueAsString(toolWrapper.getDefinition().getFunction().getParameters());
                        } catch (Exception e) {
                            log.error("Failed to serialize schema for tool {}", getName(), e);
                            return "{}";
                        }
                    }

                    @Override
                    public String call(String functionInput) {
                        log.info("[Function Calling] 收到模型调用请求: 工具={}, 参数={}", getName(), functionInput);
                        try {
                            // 1.执行apply
                            Object result = execute(getName(), functionInput);
                            // 2.将工具执行的Response转化Json给AI
                            return objectMapper.writeValueAsString(result);
                        } catch (Exception e) {
                            log.error("Failed to execute or serialize result for tool {}", getName(), e);
                            throw new RuntimeException(e);
                        }

                    }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 执行工具中的apply方法
     * @param toolName 工具bean name
     * @param arguments 源代码
     * @return 返回实体类结果
     */
    public Object execute(String toolName, String arguments) {
       // 1.先从本地tools根据bean name获取工具封装对象
        ToolWrapper toolWrapper = tools.get(toolName);
        if (toolWrapper == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        try {
            // 2.将源码转化为Request实体类
            Object request = objectMapper.readValue(arguments, toolWrapper.getInputType());
            // 3.执行工具的apply方法
            return toolWrapper.getFunction().apply((Object) request);

        } catch (Exception e) {
            log.error("Error executing tool {}", toolName, e);
            throw new RuntimeException("Tool execution failed: " + e.getMessage());
        }
    }

    @Data
    @AllArgsConstructor
    private static class ToolWrapper {
        private ToolDefinition definition;
        private Function function;
        private Class<?> inputType;
    }

}
