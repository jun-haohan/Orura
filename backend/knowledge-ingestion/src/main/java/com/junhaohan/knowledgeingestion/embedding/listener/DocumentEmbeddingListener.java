package com.junhaohan.knowledgeingestion.embedding.listener;

import com.junhaohan.knowledgeingestion.embedding.event.DocumentEmbeddingEvent;
import com.junhaohan.knowledgeingestion.embedding.service.DocumentEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听文档向量化事件并异步执行向量化。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEmbeddingListener {

    private final DocumentEmbeddingService documentEmbeddingService;

    /**
     * 异步处理文档向量化任务。
     */
    @Async
    @EventListener
    public void handle(DocumentEmbeddingEvent event) {
        try {
            documentEmbeddingService.embedDocument(event.documentId());
        } catch (Exception e) {
            log.error(
                    "Async document embedding failed, documentId={}",
                    event.documentId(),
                    e
            );
        }
    }
}