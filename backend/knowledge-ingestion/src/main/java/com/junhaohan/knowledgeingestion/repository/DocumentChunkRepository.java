package com.junhaohan.knowledgeingestion.repository;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String> {
}