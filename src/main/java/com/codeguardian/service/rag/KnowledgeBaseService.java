package com.codeguardian.service.rag;

import com.codeguardian.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @description: 知识库服务(RAG核心实现)
 * 该服务实现了 Hybrid Retrieval (混合检索) + Rerank (重排序) 策略。
 * 使用 PGVector 作为向量数据库，同时维护内存中的 BM25 索引以支持混合检索。
 * @author: Winston
 * @date: 2026/3/7 13:35
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final ObjectMapper objectMapper;
    // Injected (PGVector)
    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository repository;
    private final MinioStorageService minioStorageService;
    private final JdbcTemplate jdbcTemplate;

    // 原始文档列表（用于BM25的构建）属于本地内存中，最开始需要从数据库获取
    private List<KnowledgeDocument> documents = new ArrayList<>();
    /**
     * BM25索引结构
     */
    // 倒排索引构建
    private Map<String, List<Integer>> invertedIndex =  new HashMap<>();
    // 词频和长度惩罚
    private List<Map<String, Integer>> docTermFreqs = new ArrayList<>();
    private List<Integer> docLengths = new ArrayList<>();
    private double avgDocLength = 0;

    // BM25 算法参数
    private static final double k1 = 1.5;
    private static final double b = 0.75;

    @PostConstruct
    public void init() {
        try {
            // 1.先检查向量库是否存在，再检查向量模型是否正确
            checkAndFixVectorSchema();

            // 1.1测试是否能够查询数据，如果不能查询打出日志，不能抛出异常，不能影响其他比如构建业务
            try {
                log.info("Verifying VectorStore connection...");
                // Just check if we can query (even if empty)
                vectorStore.similaritySearch(SearchRequest.query("ping").withTopK(1));
                log.info("VectorStore connection verified successfully.");
            } catch (Exception e) {
                log.error("VectorStore connection failed during init: {}", e.getMessage());
                // Don't throw, let app start, but log error
            }

            // 2.如果数据库为空，预热到数据库
            List<KnowledgeDocument> dbDocs = repository.findAll();
            // 2.1更新字段
            // 检查是否有缺失类别的旧数据
            boolean hasNullCategory = false;
            for  (KnowledgeDocument doc : dbDocs) {
                if (doc.getCategory() == null) {
                    // 默认值
                    doc.setCategory("CODE_STYLE");
                    repository.save(doc);
                    hasNullCategory = true;
                }
            }
            // 没有缺失数据
            if (hasNullCategory) {
                // 重新加载
                log.info("Fixed missing categories for existing documents.");
                dbDocs = repository.findAll();
            }

            if (dbDocs.isEmpty()) {
                log.info("Database is empty. Loading default knowledge base from rules.json...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();
            } else {
                // 3.不为空，也需要预热到数据库，可能中途json文件修改，覆盖原来获取最新
                // 确保默认规则是最新的（覆盖旧数据）
                log.info("Reloading default knowledge base to ensure data consistency...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();

                log.info("Loaded {} documents from database.", dbDocs.size());
            }

            // 4.构建本地索引
            this.documents = dbDocs;
            buildIndices();
        } catch (Exception e) {
            log.warn("KnowledgeBaseService initialization failed: {}", e.getMessage());
        }
    }

    /**
     *  检查向量库是否存在，本地向量模型是否是384维度
     *  如果模型不一致，删除向量库
     */
    private void checkAndFixVectorSchema() {
        try {
            // Check if vector_store table exists
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'vector_store'", Integer.class);

            if (count != null && count > 0) {
                // Check embedding column type
                String type = jdbcTemplate.queryForObject(
                        "SELECT format_type(atttypid, atttypmod) FROM pg_attribute " +
                                "WHERE attrelid = 'vector_store'::regclass AND attname = 'embedding'", String.class);

                log.info("Current vector_store.embedding type: {}", type);

                // If type exists and is NOT vector(384), fix it
                if (type != null && !type.contains("(384)")) {
                    log.warn("Detected incorrect vector dimensions (expected 384). Fixing schema...");

                    // Option 1: Drop table (Cleanest, but requires restart or re-init logic which is hard)
                    // Option 2: Alter table (Preserves table, but clears data)

                    log.info("Truncating vector_store table...");
                    jdbcTemplate.execute("TRUNCATE TABLE vector_store");

                    log.info("Altering embedding column to vector(384)...");
                    // Using USING clause to cast (though truncation makes it empty)
                    jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(384)");

                    log.info("Schema fixed successfully. Please re-upload documents if needed.");
                }
            }
        } catch (Exception e) {
            log.error("Failed to check/fix vector schema: {}", e.getMessage());
            // Don't block startup
        }
    }

    /**
     * 加载默认知识库 (knowledge/rules.json)
     * 用来预热到数据库和向量库中，提供给大模型使用
     */
    private void loadDefaultKnowledgeBase() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/rules.json");
            if (resource.exists()) {
                List<java.util.Map<String, Object>> items = objectMapper.readValue(resource.getInputStream(), new TypeReference<List<Map<String, Object>>>() {});

                for (java.util.Map<String, Object> item : items) {
                    String id = item.get("id") != null ? String.valueOf(item.get("id")) : UUID.randomUUID().toString();
                    String title = item.get("title") != null ? String.valueOf(item.get("title")) : "未命名文档";
                    String content = item.get("content") != null ? String.valueOf(item.get("content")) : "";
                    String solution = item.get("solution") != null ? String.valueOf(item.get("solution")) : null;
                    String catCode = item.get("category") != null ? String.valueOf(item.get("category")).toUpperCase() : "CODE_STYLE";

                    KnowledgeDocument doc = KnowledgeDocument.builder()
                            .id(id)
                            .title(title)
                            .content(content)
                            .solution(solution)
                            .category(catCode)
                            .createTime(java.time.LocalDateTime.now())
                            .metadata(java.util.Map.of("source", "default_rules"))
                            .build();
                    saveDocument(doc);
                }
            } else {
                log.warn("Knowledge Base file not found: knowledge/rules.json");
            }
        } catch (IOException e) {
            log.error("Failed to load Knowledge Base", e);
        }
    }


    /**
     * 保存文档到 DB 和 VectorStore(向量库)
     */
    private void saveDocument(KnowledgeDocument doc) {
        // 1.保存到数据库中,并更新本地文档
        repository.save(doc);
        this.documents.add(doc);

        // 2.使用 TokenTextSplitter 切分
        // 默认参数: defaultChunkSize = 800, minChunkSizeChars = 350, minChunkLengthToEmbed = 5, maxNumChunks = 10000, keepSeparator = true
        String content = doc.getTitle() + "\n" + doc.getContent();
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(
                List.of(new Document(doc.getId(), content, doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>()))
        );

        if (chunks.isEmpty()) {
            log.warn("Document splitting resulted in 0 chunks for doc: {}", doc.getTitle());
        } else {
            log.info("Document split into {} chunks for vectorization.", chunks.size());
        }
        // 设置每一块的唯一标识,文章id
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source_doc_id", doc.getId());
        }

        // 3.保存到向量库中
        try {
            log.info("Adding {} chunks to Vector Store...", chunks.size());
            vectorStore.add(chunks);
            log.info("Successfully added chunks to Vector Store.");
        } catch (Exception e) {
            log.error("Failed to add document to Vector Store: {}", e.getMessage(), e);
            // Consider rethrowing or handling (e.g. marking doc as failed in DB)
        }

    }

    /**
     * 构建 BM25 倒排索引和统计信息 (全量)
     */
    private void buildIndices() {
        // 1.先清空本地再重新构建
        if (documents.isEmpty()) return;

        invertedIndex.clear();
        docTermFreqs.clear();
        docLengths.clear();
        long totalLength = 0;

        // 2.遍历本地文章构建
        for (int i = 0; i < documents.size(); i++) {
            addToBM25Index(documents.get(i), i);
            totalLength += docLengths.get(i);
        }

        avgDocLength = (double) totalLength / documents.size();
        log.info("Built BM25 Index for {} documents", documents.size());
    }

    /**
     * 构建BM25，包括倒排索引，逆文档词频，词频，长度；这里只是针对一篇文章的构建
     * @param doc 知识库文章
     * @param index 文章索引, 倒排索引使用
     */
    private void addToBM25Index(KnowledgeDocument doc, int index) {
        // 1.拼接标题和内容并分块
        String text = (doc.getTitle() + " " + doc.getContent()).toLowerCase();
        Map<String, Integer> freqs = new HashMap<>();
        List<String> terms = tokenize(text);

        // 2.遍历分块构建词频
        for (String term : terms) {
            freqs.put(term, freqs.getOrDefault(term, 0) + 1);
        }
        // 添加到本地构建
        docTermFreqs.add(freqs);
        docLengths.add(terms.size());

        // 3.构建倒排索引
        for (String term : freqs.keySet()) {
            invertedIndex.computeIfAbsent(term, k -> new ArrayList<>()).add(index);
        }
        // 4.更新平均长度
        // 这里需要获取本地文档长度 - 1；因为此时的此时的文档长度加上了现在即将更新文章的索引
        if (avgDocLength > 0) {
            long totalLength = (long) avgDocLength * (documents.size() - 1) + terms.size();
            avgDocLength = (double) totalLength / documents.size();
        }
    }

    /**
     * 上传并处理文档 (支持多种格式)
     */
    public void uploadDocument(MultipartFile file) throws IOException {
        log.info("Starting document upload process for file: {}, size: {}", file.getOriginalFilename(), file.getSize());
        try {
            // 1.上传到Minio
            log.info("Uploading file to MinIO...");
            String objectName = minioStorageService.uploadFile(file);
            log.info("File uploaded to MinIO. ObjectName: {}", objectName);

            log.info("Extracting text from document using Tika...");

            // 2.使用TikaDocumentReader读取任何文件形式的内容
            Resource resource = new InputStreamResource(file.getInputStream());
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> tikaDocs = tikaReader.get();

            // 读取结果为集合，拼接在一起返回字符串
            StringBuilder textBuilder = new StringBuilder();
            for  (Document tikaDoc : tikaDocs) {
                textBuilder.append(tikaDoc.getContent()).append("\n");
            }
            String text = textBuilder.toString();
            log.info("Text extraction completed. Text length: {}", text.length());

            String id = UUID.randomUUID().toString();
            KnowledgeDocument doc = KnowledgeDocument.builder()
                    .id(id)
                    .title(file.getOriginalFilename())
                    .content(text)
                    .category("CODE_STYLE")
                    .minioBucketName(minioStorageService.getBucketName())
                    .minioObjectName(objectName)
                    .fileSize(file.getSize())
                    .contentType(file.getContentType())
                    .createTime(java.time.LocalDateTime.now())
                    .metadata(Map.of(
                            "filename", file.getOriginalFilename(),
                            "type", file.getContentType() != null ? file.getContentType() : "unknown",
                            "bucket", minioStorageService.getBucketName(),
                            "object", objectName
                    ))
                    .build();
            log.info("Saving document metadata to database and vector store...");
            // 3.构建实体类，并保存到DB和Vector Store
            saveDocument(doc);
            log.info("Document saved successfully. ID: {}", id);
            // 4.并更新BM25索引
            log.info("Updating BM25 index...");
            addToBM25Index(doc, this.documents.size() - 1);
            log.info("BM25 index updated.");
        } catch (Exception e) {
            log.error("Error during document upload process", e);
            throw e;
        }

    }

    /**
     * 模糊查询，忽略大小写，如果为空放置在结果集最后面
     */
    public Page<KnowledgeDocument> getDocuments(int page, int size, String keyword) {
        // 使用 Sort.unsorted()，因为排序已经在 Repository 的 @Query 中指定了
        Pageable pageable = PageRequest.of(page - 1, size, Sort.unsorted());

        if (keyword != null && !keyword.isEmpty()) {
            return repository.findByTitleContainingIgnoreCaseNullsLast(keyword, pageable);
        }
        return repository.findAllNullsLast(pageable);
    }

    public KnowledgeDocument getDocumentById(String id) {
        return repository.findById(id).orElse(null);
    }

    public InputStream getFileStream(String objectName) {
        return minioStorageService.getFile(objectName);
    }

    public List<KnowledgeDocument> getAllDocuments() {
        return this.documents;
    }


    public Map<String, Object> getStats() {
        long count = repository.count();
        // Calculate total size if possible, or just return count for now
        // Assuming we want a simple stats object
        Map<String, Object> stats = new HashMap<>();
        stats.put("documentCount", count);
        stats.put("name", "默认知识库");
        stats.put("description", "系统默认的向量知识库，存储所有上传的代码规范和技术文档");
        // Placeholder
        stats.put("createTime", java.time.LocalDateTime.now());
        return stats;
    }
    /**
     * 删除指定的文档
     */
    public void deleteDocument(String id) {
        // 1.删除Minio上的文档
        Optional<KnowledgeDocument> docOpt = repository.findById(id);

        if (docOpt.isEmpty()) {
            return;
        }
        KnowledgeDocument doc = docOpt.get();

        if (doc.getMinioObjectName() != null) {
            try {
                minioStorageService.removeFile(doc.getMinioObjectName());
            } catch (Exception e) {
                log.error("Failed to delete file from MinIO: {}", e.getMessage());
            }
        }
        // 2.删除数据库
        repository.deleteById(id);

        // 3.删除向量库
        try {
            log.info("Deleting chunks from Vector Store for document: {}", id);
            // 利用 PostgreSQL 对 JSONB 的原生查询语法 (->>)，精准击杀所有带有该外键的切片
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source_doc_id' = ?", id);
            log.info("Successfully deleted chunks from Vector Store.");
        } catch (Exception e) {
            log.error("Failed to delete chunks from Vector Store: {}", e.getMessage());
        }
        // 4.删除本地缓存
        this.documents.removeIf(d -> d.getId().equals(id));

        // 5.重构本地BM25
        buildIndices();
    }

    /**
     * 使用BM25检索
     * @param query 用户的问题
     * @param topK 前K个得分
     * @return 返回前K个文章id
     */
    private List<Integer> searchBM25(String query, int topK) {
        // 1.先将用户的问题拆分分块并遍历
        List<String> queryTerms = tokenize(query.toLowerCase());

        Map<Integer, Double> scores = new HashMap<>();
        for (String term : queryTerms) {
            // 2.使用倒排索引，确定在哪些篇章
            List<Integer> docIndices = invertedIndex.get(term);
            if (docIndices == null) {
                continue;
            }
            // 3.计算逆文档频率，也就是稀有程度，出现的越少，越稀有，权重越高
            double idf = Math.log(1 + (documents.size() - docIndices.size() + 0.5) / (docIndices.size() + 0.5));


            // 针对一篇文章下的
            for  (Integer docIdx : docIndices) {
                // 4.计算词频，以及长度惩罚，如果在某一篇文章出现得多，文章越短，权重越高
                // 防御性编程
                if (docIdx >= docTermFreqs.size()) continue;
                int freq = docTermFreqs.get(docIdx).getOrDefault(term, 0);
                int docLen = docLengths.get(docIdx);
                double numerator = freq * (k1 + 1);
                double denominator = freq + k1 * (1 - b + b * (docLen / avgDocLength));

                scores.put(docIdx, scores.getOrDefault(docIdx, 0.0) + idf * numerator / denominator);



            }

        }
        // 5.排序得分并获取前k个文章id
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

    }

    private String sanitizeRagText(String text) {
        if (text == null) return "";
        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : lines) {
            String normalized = line.replace('\u00A0', ' ');
            String trimmed = normalized.trim();
            boolean isPageNum = trimmed.matches("^\\d+\\s*/\\s*\\d+$");
            boolean isDashBanner = trimmed.matches("^—{2,}.*—{2,}$");
            boolean containsCopyright = trimmed.contains("禁止用于商业用途") || trimmed.contains("违者必究");
            boolean isHeader = trimmed.contains("阿里巴巴") && trimmed.contains("Java") && trimmed.contains("开发手册");
            if (isPageNum || isDashBanner || containsCopyright || isHeader) {
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!prevBlank) {
                    sb.append('\n');
                    prevBlank = true;
                }
            } else {
                sb.append(normalized).append('\n');
                prevBlank = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * RRF算法重排序，将向量库的排行和BM25排行重新排序
     */
    private List<KnowledgeDocument> mergeAndRerank(List<Document> vectorDocs, List<Integer> bm25Indices, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        int k = 60;

        // 遍历向量检索排名前面的计算得分并映射对应的文章id
        if (vectorDocs != null) {
            for (int i = 0; i < vectorDocs.size(); i++) {
                Document doc = vectorDocs.get(i);
                // 尝试从 metadata 获取 source_doc_id，如果不存在则使用 docId
                String id = (String) doc.getMetadata().getOrDefault("source_doc_id", doc.getId());
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }
        // 遍历BM25检索排名前面的计算得分并映射到对应文章id
        for (int i = 0; i < bm25Indices.size(); i++) {
            if (bm25Indices.get(i) < documents.size()) {
                String id = documents.get(bm25Indices.get(i)).getId();
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> findDocById(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    private KnowledgeDocument findDocById(String id) {
        return documents.stream().filter(d -> d.getId().equals(id)).findFirst().orElse(null);
    }

    /**
     * 搜索相关片段（返回切片后的文本，而不是完整文档）
     * 用于 RAG 上下文构建，避免 Token 超限
     */
    public List<String> searchSnippets(String query, int topK) {
        // 1. 优先使用向量检索获取精确片段
        try {
            List<Document> vectorResults = vectorStore.similaritySearch(
                    SearchRequest.query(query).withTopK(topK)
            );

            if (!vectorResults.isEmpty()) {
                log.info("Found {} snippets via Vector Search", vectorResults.size());
                return vectorResults.stream()
                        .map(doc -> {
                            String title = (String) doc.getMetadata().getOrDefault("title", "");
                            String content = sanitizeRagText(doc.getContent());
                            String formatted = title.isEmpty() ? content : "【" + title + "】\n" + content;
                            return formatted;
                        })
                        .filter(s -> s != null && !s.trim().isEmpty())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
        }

        // 2. 如果向量检索无果，回退到混合检索（但截断内容）
        log.info("Vector search empty/failed, falling back to document search");
        List<KnowledgeDocument> docs = search(query, topK);
        return docs.stream()
                .map(doc -> {
                    String content = doc.getContent();
                    String title = doc.getTitle();
                    String combined = sanitizeRagText("【" + title + "】\n" + content);
                    if (combined.length() > 800) {
                        return combined.substring(0, 800) + "... (truncated)";
                    }
                    return combined;
                })
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 执行混合检索 (Hybrid Search)
     */
    public List<KnowledgeDocument> search(String query, int topK) {
        if (documents.isEmpty()) return Collections.emptyList();

        // 1. 向量检索
        List<Document> vectorResults = Collections.emptyList();
        try {
            vectorResults = vectorStore.similaritySearch(
                    SearchRequest.query(query).withTopK(topK)
            );
        } catch (Exception e) {
            log.debug("Vector search failed (using BM25 only): {}", e.getMessage());
        }

        // 2. BM25 检索
        List<Integer> bm25Indices = searchBM25(query, topK);

        // 3. 结果融合与重排序
        return mergeAndRerank(vectorResults, bm25Indices, topK);
    }


    /**
     * 简单分词 (按空格和标点)
     * 支持中文分词需要引入更复杂的库 (如 Jieba, HanLP)，这里使用正则简单处理
     */
    private List<String> tokenize(String text) {
        // 匹配中文，英文单词，数字
        List<String> tokens = new ArrayList<>();
        // 创建正则表达式模具
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]|[a-zA-Z0-9]+");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
