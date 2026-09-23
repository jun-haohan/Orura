package com.junhaohan.knowledgeingestion.retrieval.service;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;

import java.util.List;

/**
 * 检索结果重排服务。
 */
public interface RerankService {

    /** 根据查询对候选结果重新排序。 */
    List<RetrievalResult> rerank(
            String query,
            List<RetrievalResult> candidates,
            int topK
    );
}
