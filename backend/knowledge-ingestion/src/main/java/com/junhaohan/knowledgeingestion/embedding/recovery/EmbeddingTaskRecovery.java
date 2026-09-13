package com.junhaohan.knowledgeingestion.embedding.recovery;

import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.embedding.event.DocumentEmbeddingEvent;
import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SpringBoot 启动完成后恢复未执行的 PENDING 向量化任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingTaskRecovery {

    private final KnowledgeDocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 应用启动完成后恢复 PROCESSING 和 PENDING 状态的向量化任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverEmbeddingTasks() {
        recoverInterruptedTasks();
        recoverPendingTasks();
    }

    /**
     * 将旧进程遗留的 PROCESSING 任务恢复为 PENDING。
     */
    private void recoverInterruptedTasks() {
        List<KnowledgeDocument> documents =
                documentRepository.findByEmbeddingStatus(
                        EmbeddingStatus.PROCESSING
                );

        if (documents.isEmpty()) {
            return;
        }

        for (KnowledgeDocument document : documents) {
            document.setEmbeddingStatus(EmbeddingStatus.PENDING);
            document.setEmbeddingErrorMessage(null);
            document.setEmbeddedAt(null);
        }

        documentRepository.saveAll(documents);

        log.info(
                "Recovered {} interrupted embedding tasks to PENDING",
                documents.size()
        );
    }

    /**
     * 应用启动完成后扫描并重新提交 PENDING 向量化任务。
     */
    public void recoverPendingTasks() {

        List<KnowledgeDocument> documents =
                documentRepository.findByEmbeddingStatus(
                        EmbeddingStatus.PENDING
                );

        if (documents.isEmpty()) {
            log.info("No pending embedding tasks found");
            return;
        }

        log.info(
                "Found {} pending embedding tasks, start recovery",
                documents.size()
        );

        for (KnowledgeDocument document : documents) {
            publishEmbeddingEvent(document.getId());
        }
    }

    /**
     * 发布指定文档的向量化事件。
     */
    private void publishEmbeddingEvent(String documentId) {
        try {
            eventPublisher.publishEvent(
                    new DocumentEmbeddingEvent(documentId)
            );

            log.info(
                    "Recovered pending embedding task, documentId={}",
                    documentId
            );

        } catch (Exception e) {
            log.error(
                    "Failed to recover pending embedding task, documentId={}",
                    documentId,
                    e
            );
        }
    }
}