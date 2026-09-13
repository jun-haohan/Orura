package com.junhaohan.knowledgeingestion.retrieval.dto;

import lombok.Data;

/**
 * 定义语义检索请求参数。
 */
@Data
public class SemanticSearchRequest {

    private String query;

    private Integer topK = 5;
}
