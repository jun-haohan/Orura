package com.junhaohan.knowledgeingestion.domain;

import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.security.auth.callback.LanguageCallback;
import java.time.LocalDateTime;

/**
 * 文档表
 */
@Data
@Document("knowledge_document")
public class KnowledgeDocument {

    @Id
    private String id;

    private String fileName;

    private String fileType;

    private String storagePath;

    private Integer chunkCount;

    private LocalDateTime createdAt;

    private String status;        // 解析状态 SUCCESS / FAILED / PARSING

    private String errorMessage;  // 失败原因

    private LocalDateTime updatedAt;

    private String parser;

    private String contentFormat;

    private Integer contentLength;

    // 向量化状态

    private EmbeddingStatus embeddingStatus; // 是否已写入向量库

    private String embeddingErrorMessage;

    private LocalDateTime embeddingStartedAt;

    private LocalDateTime embeddedAt;
}