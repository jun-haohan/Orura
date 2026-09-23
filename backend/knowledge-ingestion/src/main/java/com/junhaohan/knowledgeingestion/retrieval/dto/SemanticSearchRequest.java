package com.junhaohan.knowledgeingestion.retrieval.dto;

import lombok.Data;

import java.util.List;

/**
 * 定义语义检索请求参数。
 */
@Data
public class SemanticSearchRequest {

    private String query;

    List<String> documentIds;

    private Integer topK = 5;
}
