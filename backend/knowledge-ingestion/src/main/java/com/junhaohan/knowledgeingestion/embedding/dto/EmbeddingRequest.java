package com.junhaohan.knowledgeingestion.embedding.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmbeddingRequest {
    private List<String> texts;
}
