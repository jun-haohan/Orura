package com.junhaohan.knowledgeingestion.retrieval.retriever;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;

import java.util.List;

/**
 * 统一检索器接口。
 */
public interface Retriever {

    /** 执行检索并返回统一结果。 */
    List<RetrievalResult> retrieve(RetrievalRequest request);
}