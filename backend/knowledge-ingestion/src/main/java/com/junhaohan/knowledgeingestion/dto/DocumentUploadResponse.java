package com.junhaohan.knowledgeingestion.dto;

import lombok.Data;

@Data

public class DocumentUploadResponse {

    private String documentId;

    private Integer chunkCount;

    public DocumentUploadResponse(String documentId, Integer chunkCount) {
        this.documentId = documentId;
        this.chunkCount = chunkCount;
    }
}