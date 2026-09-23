package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.service.RetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一检索接口。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    /** 初始化检索控制器。 */
    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /** 执行统一知识检索。 */
    @PostMapping("/search")
    public List<RetrievalResult> search(
            @RequestBody RetrievalRequest request) {
        return retrievalService.retrieve(request);
    }
}
