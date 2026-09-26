package com.junhaohan.knowledgeingestion.rag.model;

import java.util.List;

/** 单轮 RAG 问答结果。 */
public record RagResponse(
        RagStatus status,
        String answer,
        List<RagCitation> citations
) {
}