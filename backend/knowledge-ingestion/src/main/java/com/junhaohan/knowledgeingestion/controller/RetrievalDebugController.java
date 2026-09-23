package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import com.junhaohan.knowledgeingestion.retrieval.retriever.HybridRetriever;
import com.junhaohan.knowledgeingestion.retrieval.retriever.KeywordRetriever;
import com.junhaohan.knowledgeingestion.retrieval.retriever.VectorRetriever;
import com.junhaohan.knowledgeingestion.retrieval.search.SearchIndexService;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexHit;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/retrieval/debug")
public class RetrievalDebugController {

    private final SearchIndexService searchIndexService;
    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final HybridRetriever hybridRetriever;

    /** 初始化检索调试接口。 */
    public RetrievalDebugController(
            SearchIndexService searchIndexService, VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever, HybridRetriever hybridRetriever) {
        this.searchIndexService = searchIndexService;
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.hybridRetriever = hybridRetriever;
    }

    /**
     * 测试 Elasticsearch BM25 检索。
     */
    @GetMapping("/keyword")
    public List<SearchIndexHit> keyword(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) List<String> documentIds) {

        return searchIndexService.search(
                new SearchIndexRequest(
                        query,
                        topK,
                        null,
                        documentIds
                )
        );
    }

    /**
     * 将指定文档的 Chunk 重新写入 Elasticsearch。
     */
    @PostMapping("/index/{documentId}")
    public void index(@PathVariable String documentId) {
        searchIndexService.indexDocument(documentId);
    }

    /**
     * 测试向量检索。
     */
    @PostMapping("/vector")
    public List<RetrievalResult> vector(
            @RequestBody RetrievalRequest request) {
        return vectorRetriever.retrieve(request);
    }

    /**
     * 测试BM25统一检索。
     */
    @PostMapping("/keyword-retriever")
    public List<RetrievalResult> keywordRetriever(
            @RequestBody RetrievalRequest request) {
        return keywordRetriever.retrieve(request);
    }

    /**
     * 测试混合检索。
     */
    @PostMapping("/hybrid")
    public List<RetrievalResult> hybrid(
            @RequestBody RetrievalRequest request) {
        return hybridRetriever.retrieve(request);
    }

    /**
     * 查询指定文档在 Elasticsearch 中的 Chunk 数量。
     */
    @GetMapping("/chunk-count")
    public long chunkCount(@RequestParam String documentId) {
        return searchIndexService.countByDocumentId(documentId);
    }
}