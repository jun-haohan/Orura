package com.junhaohan.knowledgeingestion.retrieval.fusion;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RRF检索结果融合服务。
 */
@Service
public class RrfFusionService {

    private static final int RRF_K = 60;

    /**
     * 融合向量检索和关键词检索结果。
     */
    public List<RetrievalResult> fuse(
            List<RetrievalResult> vectorResults,
            List<RetrievalResult> keywordResults,
            int topK) {

        Map<String, Double> scores = new HashMap<>();
        Map<String, RetrievalResult> resultMap = new HashMap<>();

        accumulate(vectorResults, scores, resultMap);
        accumulate(keywordResults, scores, resultMap);

        AtomicInteger rank = new AtomicInteger(1);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalResult origin = resultMap.get(entry.getKey());

                    return new RetrievalResult(
                            origin.chunkId(),
                            origin.documentId(),
                            origin.content(),
                            entry.getValue(),
                            rank.getAndIncrement(),
                            RetrievalSource.HYBRID
                    );
                })
                .toList();
    }

    /**
     * 累加单路检索结果的RRF分数。
     */
    private void accumulate(
            List<RetrievalResult> results,
            Map<String, Double> scores,
            Map<String, RetrievalResult> resultMap) {

        for (RetrievalResult result : results) {
            double score = 1.0 / (RRF_K + result.rank());

            scores.merge(result.chunkId(), score, Double::sum);
            resultMap.putIfAbsent(result.chunkId(), result);
        }
    }
}