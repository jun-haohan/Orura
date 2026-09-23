package com.junhaohan.knowledgeingestion.retrieval.model;

import java.util.List;

/**
 * 检索过滤条件。
 */
public record RetrievalFilter(
        List<String> documentIds
) {
}