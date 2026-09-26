package com.junhaohan.knowledgeingestion.rag.controller;

import com.junhaohan.knowledgeingestion.rag.model.RagRequest;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.service.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供单轮知识库问答接口。 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    /** 注入 RAG 问答服务。 */
    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 接受问题及可选检索条件，返回答案和有效引用。 */
    @PostMapping("/ask")
    public RagResponse ask(@RequestBody RagRequest request) {
        return ragService.ask(request);
    }
}