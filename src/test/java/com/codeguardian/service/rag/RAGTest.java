package com.codeguardian.service.rag;

import com.codeguardian.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.checkerframework.checker.units.qual.K;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @description: RAG测试
 * @author: Winston
 * @date: 2026/3/15 8:59
 * @version: 1.0
 */
@ExtendWith(MockitoExtension.class)
public class RAGTest {

    @Mock
    private KnowledgeDocumentRepository repository;

    @Mock MinioStorageService minioStorageService;

    @Mock
    private VectorStore vectorStore;


    @Mock
    private JdbcTemplate jdbcTemplate;


    private KnowledgeBaseService knowledgeBaseService;

    private KnowledgeDocument  knowledgeDocument;
    
    @BeforeEach
    public void setup() {

        // 【关键修复 2】使用真实的 ObjectMapper，防止读取 rules.json 时报空指针
        ObjectMapper realObjectMapper = new ObjectMapper();

        // 手动把真实的和 Mock 的依赖塞进构造函数里
        knowledgeBaseService = new KnowledgeBaseService(
                realObjectMapper, vectorStore, repository, minioStorageService, jdbcTemplate
        );

        knowledgeDocument = KnowledgeDocument.builder()
                .id("text_id_1")
                .title("Java开发规范")
                .content("为了保证代码可读性，强烈建议使用策略模式替代过多的if-else。另外，重试机制应该在架构层面统一实现。")
                .category("CODE_STYLE")
                .createTime(LocalDateTime.now())
                .build();

        when(repository.findAll()).thenReturn(List.of(knowledgeDocument));

        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("vector(384)");

        knowledgeBaseService.init();

        // 【新增】清空 init() 阶段产生的调用记录，让后续的 verify 重新从 0 开始计数
        Mockito.clearInvocations(vectorStore);

    }

    @Test
    @DisplayName("测试双路召回并RRF融合，正常运行")
    public void RRFTest(){
        // 向量库结果
        String chunkId = "text_id_1_chunk_0";
        Document document = new Document("vector_id", "强烈建议使用策略模式", Map.of("chunk_id", chunkId));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        // 测试，BM25结果应该为 “策略”“模式”；
        List<String> strings = knowledgeBaseService.searchSnippets("策略模式", 5);

        // 验证
        assertFalse(strings.isEmpty(), "双路召回失败");
        assertTrue(strings.get(0).contains("【Java开发规范】"), "应该包含格式化后的标题");
        assertTrue(strings.get(0).contains("策略模式替代过多的if-else"), "应该包含具体的文档切片内容");

        // 验证确实调用了向量检索
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));

    }

    @Test
    @DisplayName("测试向量库宕机，BM25兜底")
    public void BM25Test(){
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("PGVector Connection Timeout"));

        List<String> strings = knowledgeBaseService.searchSnippets("重试机制", 5);

        assertFalse(strings.isEmpty(), "BM25不能为空");
        assertTrue(strings.get(0).contains("重试机制应该在架构层面统一实现"), "兜底结果必须准确");


    }

    @Test
    @DisplayName("测试双路都未命中")
    public void noAnswerTest(){
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<String> strings = knowledgeBaseService.searchSnippets("没有得内容", 5);

        assertTrue(strings.isEmpty(), "都没有命中");
    }
}
