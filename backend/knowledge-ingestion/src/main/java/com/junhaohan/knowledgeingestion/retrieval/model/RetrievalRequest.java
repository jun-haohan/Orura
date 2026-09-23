package com.junhaohan.knowledgeingestion.retrieval.model;

import java.util.List;

/**
 * 统一检索请求。
 */
public record RetrievalRequest(
        String query,
        RetrievalMode mode,
        Integer topK,
        Integer candidateTopK,
        Boolean rerank,
        RetrievalFilter filter
) {

    /** 获取实际 TopK。 */
    public int actualTopK() {
        return topK == null ? 10 : topK;
    }

    /** 获取实际候选数量。 */
    public int actualCandidateTopK() {
        return candidateTopK == null ? 30 : candidateTopK;
    }

    /** 获取实际检索模式。 */
    public RetrievalMode actualMode() {
        return mode == null ? RetrievalMode.HYBRID : mode;
    }

    /** 是否启用重排。 */
    public boolean rerankEnabled() {
        return Boolean.TRUE.equals(rerank);
    }
}
