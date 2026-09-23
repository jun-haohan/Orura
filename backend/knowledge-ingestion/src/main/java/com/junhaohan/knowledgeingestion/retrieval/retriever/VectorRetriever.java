package com.junhaohan.knowledgeingestion.retrieval.retriever;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalSource;
import com.junhaohan.knowledgeingestion.retrieval.service.SemanticSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量检索器，复用已有语义检索能力。
 */
@Component
public class VectorRetriever implements Retriever {

    private final SemanticSearchService semanticSearchService;

    /** 初始化向量检索器。 */
    public VectorRetriever(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    /** 执行向量检索并转换为统一检索结果。 */
    @Override
    public List<RetrievalResult> retrieve(RetrievalRequest request) {
        List<String> documentIds = request.filter() == null
                ? null
                : request.filter().documentIds();

        var results = semanticSearchService.search(
                request.query(),
                documentIds,
                request.actualCandidateTopK()
        );

        AtomicInteger rank = new AtomicInteger(1);

        return results.stream()
                .map(result -> new RetrievalResult(
                        result.getChunkId(),
                        result.getDocumentId(),
                        result.getContent(),
                        result.getScore(),
                        rank.getAndIncrement(),
                        RetrievalSource.VECTOR
                ))
                .toList();
    }
}