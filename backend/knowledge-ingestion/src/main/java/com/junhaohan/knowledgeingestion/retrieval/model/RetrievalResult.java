package com.junhaohan.knowledgeingestion.retrieval.model;

/**
 * 统一检索结果。
 */
public record RetrievalResult(
        String chunkId,
        String documentId,
        String content,
        double score,
        int rank,
        RetrievalSource source
) {
}