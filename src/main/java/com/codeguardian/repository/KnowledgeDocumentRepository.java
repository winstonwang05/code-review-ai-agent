package com.codeguardian.repository;

import com.codeguardian.service.rag.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * @description: 知识库仓库
 * @author: Winston
 * @date: 2026/3/6 14:37
 * @version: 1.0
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    /**
     * 获取最新的所有知识库
     */
    @Query("SELECT d FROM KnowledgeDocument d ORDER BY d.createTime DESC NULLS LAST")
    Page<KnowledgeDocument> findAllNullsLast(Pageable pageable);

    /**
     * 根据title模糊查询，并且忽略大小写，结果按照创建时间降序也就是获取最新
     */
    @Query("SELECT d FROM KnowledgeDocument d WHERE LOWER(d.title) LIKE LOWER(CONCAT('%', :title, '%')) ORDER BY d.createTime DESC NULLS LAST")
    Page<KnowledgeDocument> findByTitleContainingIgnoreCaseNullsLast(@Param("title") String title, Pageable pageable);

    Page<KnowledgeDocument> findByTitleContainingIgnoreCase(String title, Pageable pageable);


}
