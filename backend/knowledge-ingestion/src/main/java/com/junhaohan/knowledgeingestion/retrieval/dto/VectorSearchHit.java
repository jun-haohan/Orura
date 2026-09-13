package com.junhaohan.knowledgeingestion.retrieval.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表示 Milvus 向量检索命中结果。
 */
@Data
@AllArgsConstructor
public class VectorSearchHit {

    private String chunkId;
    private String documentId;
    private Integer chunkIndex;
    private Float score;
}
