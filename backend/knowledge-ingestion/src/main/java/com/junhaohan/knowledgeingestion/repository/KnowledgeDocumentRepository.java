package com.junhaohan.knowledgeingestion.repository;

import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;


/**
 * MongoDB Repository，用来操作文档主记录，Spring Data MongoDB 会自动提供基础数据库操作函数。
 */
public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {
}