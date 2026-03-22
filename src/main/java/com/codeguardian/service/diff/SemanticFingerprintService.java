package com.codeguardian.service.diff;

import com.codeguardian.entity.Finding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 语义指纹服务
 * 职责：
 * 1. 计算方法体的 SHA-256 指纹 key（含 model/language/kbVersion）
 * 2. Redis 缓存读写（TTL 24h）
 * 3. 管理 kbVersion（知识库更新时 INCR，使旧缓存自然失效）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticFingerprintService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.provider:QWEN}")
    private String modelName;

    public static final String KB_VERSION_KEY = "kb:version";
    private static final String FINGERPRINT_PREFIX = "diff:fingerprint:";
    private static final long FINGERPRINT_TTL_HOURS = 24;

    /**
     * 计算语义指纹 key
     * 格式：diff:fingerprint:{sha256}:{model}:{language}:{kbVersion}
     */
    public String computeKey(String strippedBody, String language) {
        String sha256 = DigestUtils.sha256Hex(strippedBody);
        String kbVersion = getKbVersion();
        return FINGERPRINT_PREFIX + sha256 + ":" + modelName + ":" + language + ":" + kbVersion;
    }

    /**
     * 查询缓存，命中返回 Finding 列表，未命中返回 empty
     */
    public Optional<List<Finding>> getFromCache(String fingerprintKey) {
        try {
            String json = redisTemplate.opsForValue().get(fingerprintKey);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            List<Finding> findings = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("[指纹缓存] 命中: key={}, findings={}", fingerprintKey, findings.size());
            return Optional.of(findings);
        } catch (Exception e) {
            log.warn("[指纹缓存] 读取失败，降级为未命中: key={}, err={}", fingerprintKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入缓存，TTL 24 小时
     */
    public void putToCache(String fingerprintKey, List<Finding> findings) {
        try {
            String json = objectMapper.writeValueAsString(findings);
            redisTemplate.opsForValue().set(fingerprintKey, json, FINGERPRINT_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[指纹缓存] 写入: key={}, findings={}", fingerprintKey, findings.size());
        } catch (Exception e) {
            log.warn("[指纹缓存] 写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 获取当前 kbVersion，不存在时初始化为 "1"
     */
    public String getKbVersion() {
        String version = redisTemplate.opsForValue().get(KB_VERSION_KEY);
        if (version == null) {
            redisTemplate.opsForValue().set(KB_VERSION_KEY, "1");
            return "1";
        }
        return version;
    }

    /**
     * 知识库更新时调用，INCR kbVersion，使所有旧指纹缓存自然失效
     * 由 KnowledgeBaseService.saveDocument / deleteDocument 调用
     */
    public void incrementKbVersion() {
        redisTemplate.opsForValue().increment(KB_VERSION_KEY);
        log.info("[指纹缓存] kbVersion 已更新: {}", getKbVersion());
    }
}
