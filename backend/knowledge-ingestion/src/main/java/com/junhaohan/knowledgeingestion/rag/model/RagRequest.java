package com.junhaohan.knowledgeingestion.rag.model;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalMode;

import java.util.List;

/** 单轮 RAG 问答请求。 */
public record RagRequest(
        String question,
        List<String> documentIds,
        RetrievalMode mode,
        Integer topK,
        Boolean rerank
) {
}