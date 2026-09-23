package com.junhaohan.knowledgeingestion.retrieval.reindex;

import com.junhaohan.knowledgeingestion.embedding.service.DocumentEmbeddingService;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.retrieval.search.SearchIndexService;
import org.springframework.stereotype.Service;

/**
 * 文档索引重建服务实现。
 */
@Service
public class ReindexServiceImpl implements ReindexService {

    private final SearchIndexService searchIndexService;
    private final DocumentEmbeddingService documentEmbeddingService;
    private final MilvusVectorStore milvusVectorStore;

    /** 初始化索引重建服务。 */
    public ReindexServiceImpl(
            SearchIndexService searchIndexService,
            DocumentEmbeddingService documentEmbeddingService,
            MilvusVectorStore milvusVectorStore) {
        this.searchIndexService = searchIndexService;
        this.documentEmbeddingService = documentEmbeddingService;
        this.milvusVectorStore = milvusVectorStore;
    }

    /** 重建指定文档的Milvus和Elasticsearch索引。 */
    @Override
    public void reindex(String documentId) {

        // 1. 删除旧 Milvus 向量
        milvusVectorStore.deleteByDocumentId(documentId);

        // 2. 删除旧 Elasticsearch 索引
        searchIndexService.deleteByDocumentId(documentId);

        // 3. 基于 MongoDB Chunk 重新向量化并写入 Milvus
        documentEmbeddingService.embedDocument(documentId);

        // 4. 基于 MongoDB Chunk 重新写入 Elasticsearch
        searchIndexService.indexDocument(documentId);
    }
}