package com.junhaohan.knowledgeingestion.retrieval.service;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.retriever.HybridRetriever;
import com.junhaohan.knowledgeingestion.retrieval.retriever.KeywordRetriever;
import com.junhaohan.knowledgeingestion.retrieval.retriever.VectorRetriever;
import com.junhaohan.knowledgeingestion.retrieval.service.RerankService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一检索服务实现。
 */
@Service
public class RetrievalServiceImpl implements RetrievalService {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;

    /** 初始化统一检索服务。 */
    public RetrievalServiceImpl(
            VectorRetriever vectorRetriever,
            KeywordRetriever keywordRetriever,
            HybridRetriever hybridRetriever,
            RerankService rerankService) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.hybridRetriever = hybridRetriever;
        this.rerankService = rerankService;
    }

    /** 根据检索模式执行对应检索策略。 */
    @Override
    public List<RetrievalResult> retrieve(RetrievalRequest request) {
        List<RetrievalResult> results = switch (request.actualMode()) {
            case VECTOR -> vectorRetriever.retrieve(request);
            case KEYWORD -> keywordRetriever.retrieve(request);
            case HYBRID -> hybridRetriever.retrieve(request);
        };

        if (request.rerankEnabled() && !results.isEmpty()) {
            return rerankService.rerank(
                    request.query(),
                    results,
                    request.actualTopK()
            );
        }

        return results.stream()
                .limit(request.actualTopK())
                .toList();
    }
}
