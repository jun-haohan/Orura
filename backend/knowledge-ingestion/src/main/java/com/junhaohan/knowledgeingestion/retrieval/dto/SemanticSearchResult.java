package com.junhaohan.knowledgeingestion.retrieval.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表示最终返回给调用方的语义检索结果。
 */
@Data
@AllArgsConstructor
public class SemanticSearchResult {

    private String chunkId;
    private String documentId;
    private Integer chunkIndex;
    private String content;
    private Float score;
}