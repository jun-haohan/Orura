package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.embedding.service.DocumentEmbeddingService;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提供文档向量化相关接口。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentEmbeddingController {

    private final DocumentEmbeddingService documentEmbeddingService;

    /**
     * 手动执行指定文档的向量化。
     */
    @PostMapping("/{documentId}/embedding")
    public void embedDocument(@PathVariable String documentId) {
        documentEmbeddingService.embedDocument(documentId);
    }

    /**
     * 重试失败的文档向量化任务。
     */
    @PostMapping("/{documentId}/embedding/retry")
    public void retryEmbedding(@PathVariable String documentId) {
        documentEmbeddingService.retry(documentId);
    }

    /**
     * 验证指定文档对应的 Milvus 向量数量。
     */
    @PostMapping("/{documentId}/embedding/vectors")
    public long checkVectors(@PathVariable String documentId) {
        return documentEmbeddingService.checkVectors(documentId);
    }

    /**
     * 检查指定文档的 Chunk 与 Vector 数据是否一致。
     */
    @GetMapping("/{documentId}/embedding/check")
    public boolean checkEmbedding(@PathVariable String documentId) {
        return documentEmbeddingService.checkConsistency(documentId);
    }
}