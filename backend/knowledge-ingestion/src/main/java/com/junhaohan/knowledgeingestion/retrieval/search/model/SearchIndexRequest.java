package com.junhaohan.knowledgeingestion.retrieval.search.model;

import java.util.List;

/**
 * Elasticsearch关键词检索请求。
 */
public record SearchIndexRequest(
        String query,
        int topK,
        String knowledgeBaseId,
        List<String> documentIds
) {
}