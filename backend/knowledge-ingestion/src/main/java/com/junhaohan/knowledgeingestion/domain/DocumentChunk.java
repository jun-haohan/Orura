package com.junhaohan.knowledgeingestion.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document("document_chunk")
public class DocumentChunk {
    // 单个切块类

    @Id
    private String id;

    private String documentId;

    private Integer chunkIndex;

    private String content;

    private Integer charCount;

    private LocalDateTime createdAt;
}