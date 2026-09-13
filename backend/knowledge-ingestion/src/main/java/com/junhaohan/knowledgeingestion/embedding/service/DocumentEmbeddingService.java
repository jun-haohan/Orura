package com.junhaohan.knowledgeingestion.embedding.service;

import com.junhaohan.knowledgeingestion.config.EmbeddingProperties;
import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.embedding.dto.ChunkVector;
import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 负责文档向量化流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingService {

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final EmbeddingProperties embeddingProperties;

    /**
     * 文档向量化，写入milvus
     */
    public void embedDocument(String documentId) {

        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Document not found: " + documentId));

        validateDocument(document);

        List<DocumentChunk> chunks =
                chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);

        if (chunks.isEmpty()) {
            throw new IllegalStateException(
                    "No chunks found for document: " + documentId);
        }

        document.setEmbeddingStatus(EmbeddingStatus.PROCESSING);
        document.setEmbeddingErrorMessage(null);
        document.setEmbeddingStartedAt(LocalDateTime.now());
        document.setEmbeddedAt(null);
        documentRepository.save(document);

        try {
            // 防止重新向量化时存在旧数据或上次失败留下部分数据
            milvusVectorStore.deleteByDocumentId(documentId);

            int batchSize = embeddingProperties.getBatchSize();

            for (int start = 0; start < chunks.size(); start += batchSize) {

                int end = Math.min(start + batchSize, chunks.size());
                List<DocumentChunk> batch = chunks.subList(start, end);

                embedBatch(batch);
            }

            document.setEmbeddingStatus(EmbeddingStatus.SUCCESS);
            document.setEmbeddingErrorMessage(null);
            document.setEmbeddedAt(LocalDateTime.now());

            log.info(
                    "Document embedding completed, documentId={}, chunkCount={}",
                    documentId,
                    chunks.size()
            );

        } catch (Exception e) {

            document.setEmbeddingStatus(EmbeddingStatus.FAILED);
            document.setEmbeddingErrorMessage(e.getMessage());
            document.setEmbeddedAt(null);

            // 清理可能已经写入的部分向量
            try {
                milvusVectorStore.deleteByDocumentId(documentId);
            } catch (Exception cleanupException) {
                log.warn(
                        "Failed to cleanup vectors, documentId={}",
                        documentId,
                        cleanupException
                );
            }

            log.error(
                    "Document embedding failed, documentId={}",
                    documentId,
                    e
            );

            throw new IllegalStateException(
                    "Document embedding failed: " + documentId,
                    e
            );

        } finally {
            documentRepository.save(document);
        }
    }

    /**
     * 重试指定文档的向量化任务。
     */
    public void retry(String documentId) {

        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Document not found: " + documentId
                        )
                );

        if (document.getEmbeddingStatus() != EmbeddingStatus.FAILED) {
            throw new IllegalStateException(
                    "Only failed embedding task can be retried"
            );
        }

        document.setEmbeddingStatus(EmbeddingStatus.PENDING);
        document.setEmbeddingErrorMessage(null);
        document.setEmbeddedAt(null);

        documentRepository.save(document);

        embedDocument(documentId);
    }

    private void embedBatch(List<DocumentChunk> chunks) {

        List<String> texts = chunks.stream()
                .map(DocumentChunk::getContent)
                .toList();

        List<List<Float>> vectors = embeddingService.embed(texts);

        validateVectors(chunks, vectors);

        List<ChunkVector> chunkVectors = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {

            DocumentChunk chunk = chunks.get(i);

            chunkVectors.add(new ChunkVector(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getChunkIndex(),
                    vectors.get(i)
            ));
        }

        milvusVectorStore.insert(chunkVectors);
    }

    private void validateVectors(
            List<DocumentChunk> chunks,
            List<List<Float>> vectors) {

        if (vectors == null) {
            throw new IllegalStateException("Embedding vectors are null");
        }

        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch, expected="
                            + chunks.size()
                            + ", actual="
                            + vectors.size()
            );
        }

        int expectedDimension = embeddingProperties.getDimension();

        for (int i = 0; i < vectors.size(); i++) {

            List<Float> vector = vectors.get(i);

            if (vector == null || vector.size() != expectedDimension) {
                throw new IllegalStateException(
                        "Invalid embedding dimension at index="
                                + i
                                + ", expected="
                                + expectedDimension
                                + ", actual="
                                + (vector == null ? 0 : vector.size())
                );
            }
        }
    }

    /**
     * 校验文档是否允许执行向量化。
     */
    private void validateDocument(KnowledgeDocument document) {
        if (!Objects.equals(document.getStatus(), "SUCCESS")) {
            throw new IllegalStateException(
                    "Document is not parsed successfully: " + document.getId()
            );
        }

        if (document.getEmbeddingStatus() == EmbeddingStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Document embedding is already processing: " + document.getId()
            );
        }
    }

    /**
     * 验证指定文档对应的 Milvus 向量数量。
     */
    public void checkVectors(String documentId) {
        long count = milvusVectorStore.countByDocumentId(documentId);

        log.info(
                "CheckVectors: documentId={}, vectorCount={}",
                documentId,
                count
        );
    }

    /**
     * 检查指定文档的 Chunk 与 Vector 数量是否一致。
     */
    public boolean checkConsistency(String documentId) {

        long chunkCount = chunkRepository.countByDocumentId(documentId);
        long vectorCount = milvusVectorStore.countByDocumentId(documentId);

        log.info(
                "Embedding consistency check, documentId={}, chunkCount={}, vectorCount={}",
                documentId,
                chunkCount,
                vectorCount
        );

        return chunkCount == vectorCount;
    }
}