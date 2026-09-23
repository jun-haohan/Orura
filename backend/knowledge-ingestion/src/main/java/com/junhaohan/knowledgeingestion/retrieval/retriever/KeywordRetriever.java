package com.junhaohan.knowledgeingestion.retrieval.retriever;

import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalSource;
import com.junhaohan.knowledgeingestion.retrieval.search.SearchIndexService;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexHit;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BM25关键词检索器。
 */
@Component
public class KeywordRetriever implements Retriever {

    private final SearchIndexService searchIndexService;
    private final DocumentChunkRepository documentChunkRepository;

    /** 初始化关键词检索器。 */
    public KeywordRetriever(
            SearchIndexService searchIndexService,
            DocumentChunkRepository documentChunkRepository) {
        this.searchIndexService = searchIndexService;
        this.documentChunkRepository = documentChunkRepository;
    }

    /** 执行BM25关键词检索。 */
    @Override
    public List<RetrievalResult> retrieve(RetrievalRequest request) {
        List<String> documentIds = request.filter() == null
                ? null
                : request.filter().documentIds();

        SearchIndexRequest searchRequest = new SearchIndexRequest(
                request.query(),
                request.actualCandidateTopK(),
                null,
                documentIds
        );

        List<SearchIndexHit> hits = searchIndexService.search(searchRequest);

        AtomicInteger rank = new AtomicInteger(1);

        return hits.stream()
                .map(hit -> documentChunkRepository.findById(hit.chunkId())
                        .map(chunk -> new RetrievalResult(
                                chunk.getId(),
                                chunk.getDocumentId(),
                                chunk.getContent(),
                                hit.score(),
                                rank.getAndIncrement(),
                                RetrievalSource.KEYWORD
                        ))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}