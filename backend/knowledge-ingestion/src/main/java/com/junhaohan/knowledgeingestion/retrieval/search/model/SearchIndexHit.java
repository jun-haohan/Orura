package com.junhaohan.knowledgeingestion.retrieval.search.model;

/**
 * Elasticsearch检索命中结果。
 */
public record SearchIndexHit(
        String chunkId,
        String documentId,
        double score
) {
}