package com.junhaohan.knowledgeingestion.embedding.event;

/**
 * 表示文档已准备好执行向量化。
 */
public record DocumentEmbeddingEvent(String documentId) {
}