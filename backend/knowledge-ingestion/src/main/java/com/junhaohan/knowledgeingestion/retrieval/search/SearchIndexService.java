package com.junhaohan.knowledgeingestion.retrieval.search;

import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexHit;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexRequest;

import java.util.List;

public interface SearchIndexService {

    /** 将指定文档的所有 Chunk 写入搜索索引。 MongoDB Chunk → ES */
    void indexDocument(String documentId);

    /** 删除指定文档对应的所有搜索索引。 */
    void deleteByDocumentId(String documentId);

    /** 执行关键词检索。 BM25 + Metadata Filter */
    List<SearchIndexHit> search(SearchIndexRequest request);

    /**
     * 统计指定文档在 Elasticsearch 中的 Chunk 数量。
     */
    long countByDocumentId(String documentId);

    /**
     * 清空全部 Elasticsearch Chunk 数据，但保留 Index 和 Mapping。
     */
    void clearAll();
}