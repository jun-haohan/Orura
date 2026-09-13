package com.junhaohan.knowledgeingestion.embedding.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmbeddingResponse {
    private Integer dimension;
    private List<List<Float>> vectors;
}
