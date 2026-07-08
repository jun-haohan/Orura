package com.junhaohan.knowledgeingestion.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * 切块表
 */
@Data
@Document("document_chunk")
public class DocumentChunk {

    @Id
    private String id;

    private String documentId;

    private Integer chunkIndex;

    private String content;

    private Integer charCount;

    private LocalDateTime createdAt;
}