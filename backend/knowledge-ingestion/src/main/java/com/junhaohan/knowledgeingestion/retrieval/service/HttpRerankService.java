package com.junhaohan.knowledgeingestion.retrieval.service;


import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于HTTP调用模型服务的重排实现。
 */
@Service
public class HttpRerankService implements RerankService {

    private final RestClient restClient;

    /** 初始化HTTP重排服务。 */
    public HttpRerankService(
            @Value("${embedding.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** 调用Rerank模型并重新排序。 */
    @Override
    public List<RetrievalResult> rerank(
            String query,
            List<RetrievalResult> candidates,
            int topK) {

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        RerankRequest request = new RerankRequest(
                query,
                candidates.stream()
                        .map(RetrievalResult::content)
                        .toList()
        );

        RerankResponse response = restClient.post()
                .uri("/rerank")
                .body(request)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null
                || response.scores() == null
                || response.scores().size() != candidates.size()) {
            throw new IllegalStateException("Rerank返回结果数量与候选数量不一致");
        }

        return buildResults(candidates, response.scores(), topK);
    }

    /** 根据重排分数生成最终结果。 */
    private List<RetrievalResult> buildResults(
            List<RetrievalResult> candidates,
            List<Double> scores,
            int topK) {

        List<ScoredResult> scoredResults = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            scoredResults.add(
                    new ScoredResult(candidates.get(i), scores.get(i))
            );
        }

        scoredResults.sort(
                Comparator.comparingDouble(ScoredResult::score).reversed()
        );

        List<RetrievalResult> results = new ArrayList<>();

        for (int i = 0; i < Math.min(topK, scoredResults.size()); i++) {
            ScoredResult scored = scoredResults.get(i);
            RetrievalResult origin = scored.result();

            results.add(new RetrievalResult(
                    origin.chunkId(),
                    origin.documentId(),
                    origin.content(),
                    scored.score(),
                    i + 1,
                    RetrievalSource.RERANK
            ));
        }

        return results;
    }

    /** Rerank请求模型。 */
    private record RerankRequest(
            String query,
            List<String> documents
    ) {
    }

    /** Rerank响应模型。 */
    private record RerankResponse(
            List<Double> scores
    ) {
    }

    /** 候选结果与分数绑定模型。 */
    private record ScoredResult(
            RetrievalResult result,
            double score
    ) {
    }
}