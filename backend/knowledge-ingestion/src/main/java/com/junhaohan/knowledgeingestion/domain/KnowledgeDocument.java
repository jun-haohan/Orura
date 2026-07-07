package com.junhaohan.knowledgeingestion.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document("knowledge_document")
public class KnowledgeDocument {
    // 文件类

    @Id
    private String id;

    private String fileName;

    private String fileType;

    private String storagePath;

    private Integer chunkCount;

    private LocalDateTime createdAt;
}