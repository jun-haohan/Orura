package com.junhaohan.knowledgeingestion.repository;

import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;


/**
 * MongoDB Repository，用来操作文档主记录，Spring Data MongoDB 会自动提供基础数据库操作函数。
 */
public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {
    /**
     * 查询指定向量化状态的所有文档。
     */
    List<KnowledgeDocument> findByEmbeddingStatus(
            EmbeddingStatus embeddingStatus
    );
}