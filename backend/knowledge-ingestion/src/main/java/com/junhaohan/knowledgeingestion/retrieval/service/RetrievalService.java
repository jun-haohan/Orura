package com.junhaohan.knowledgeingestion.retrieval.service;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;

import java.util.List;

/**
 * 统一检索服务。
 */
public interface RetrievalService {

    /** 根据请求执行统一检索。 */
    List<RetrievalResult> retrieve(RetrievalRequest request);
}