package com.junhaohan.knowledgeingestion.embedding.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ChunkVector {

    private String chunkId;
    private String documentId;
    private Integer chunkIndex;
    private List<Float> embedding;
}