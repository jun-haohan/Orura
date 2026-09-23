package com.junhaohan.knowledgeingestion.retrieval.retriever;

import com.junhaohan.knowledgeingestion.retrieval.fusion.RrfFusionService;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 混合检索器，负责向量检索、关键词检索及RRF融合。
 */
@Component
public class HybridRetriever implements Retriever {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RrfFusionService rrfFusionService;

    /** 初始化混合检索器。 */
    public HybridRetriever(
            VectorRetriever vectorRetriever,
            KeywordRetriever keywordRetriever,
            RrfFusionService rrfFusionService) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.rrfFusionService = rrfFusionService;
    }

    /** 执行混合检索。 */
    @Override
    public List<RetrievalResult> retrieve(RetrievalRequest request) {
        List<RetrievalResult> vectorResults =
                vectorRetriever.retrieve(request);

        List<RetrievalResult> keywordResults =
                keywordRetriever.retrieve(request);

        return rrfFusionService.fuse(
                vectorResults,
                keywordResults,
                request.actualCandidateTopK()
        );
    }
}