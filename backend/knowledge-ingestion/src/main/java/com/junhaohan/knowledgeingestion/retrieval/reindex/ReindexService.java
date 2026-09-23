package com.junhaohan.knowledgeingestion.retrieval.reindex;

/**
 * 文档索引重建服务。
 */
public interface ReindexService {

    /** 重建指定文档的向量索引和关键词索引。 */
    void reindex(String documentId);
}
