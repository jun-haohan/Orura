package com.junhaohan.knowledgeingestion.repository;

import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {
}