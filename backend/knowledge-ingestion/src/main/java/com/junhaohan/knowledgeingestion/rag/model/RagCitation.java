package com.junhaohan.knowledgeingestion.rag.model;

/** 模型回答所引用的文档片段。 */
public record RagCitation(
        int ref,
        String documentId,
        String chunkId,
        String snippet
) {
}