package com.junhaohan.knowledgeingestion.retrieval.service;

import com.junhaohan.knowledgeingestion.embedding.service.EmbeddingService;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.retrieval.dto.SemanticSearchResult;
import com.junhaohan.knowledgeingestion.retrieval.dto.VectorSearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore milvusVectorStore;
    private final DocumentChunkRepository chunkRepository;

    /**
     * 根据文本 Query 返回 TopK 最相关 Chunk。
     */
    public List<SemanticSearchResult> search(
            String query,
            int topK) {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        List<List<Float>> vectors =
                embeddingService.embed(List.of(query));

        if (vectors.isEmpty()) {
            throw new IllegalStateException(
                    "Query embedding result is empty"
            );
        }

        List<VectorSearchHit> hits =
                milvusVectorStore.search(
                        vectors.get(0),
                        topK
                );

        return hits.stream()
                .map(this::loadChunk)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 根据向量命中结果加载 MongoDB 中的 Chunk 原文。
     */
    private SemanticSearchResult loadChunk(
            VectorSearchHit hit) {

        return chunkRepository.findById(hit.getChunkId())
                .map(chunk -> new SemanticSearchResult(
                        hit.getChunkId(),
                        hit.getDocumentId(),
                        hit.getChunkIndex(),
                        chunk.getContent(),
                        hit.getScore()
                ))
                .orElse(null);
    }
}
