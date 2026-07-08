package com.junhaohan.knowledgeingestion.repository;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * MongoDB Repository，用来操作文档切块记录。
 */
public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(String documentId);

    void deleteByDocumentId(String documentId);
}