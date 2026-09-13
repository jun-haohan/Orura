package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.retrieval.dto.SemanticSearchRequest;
import com.junhaohan.knowledgeingestion.retrieval.dto.SemanticSearchResult;
import com.junhaohan.knowledgeingestion.retrieval.service.SemanticSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供知识库语义检索接口。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SemanticSearchController {

    private final SemanticSearchService semanticSearchService;

    /**
     * 根据自然语言查询检索最相关的知识 Chunk。
     */
    @PostMapping("/semantic")
    public List<SemanticSearchResult> search(
            @RequestBody SemanticSearchRequest request) {

        int topK = request.getTopK() == null
                ? 5
                : request.getTopK();

        return semanticSearchService.search(
                request.getQuery(),
                topK
        );
    }
}
