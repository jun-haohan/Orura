package com.junhaohan.knowledgeingestion.service;

import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.retrieval.search.SearchIndexService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * 系统全量业务数据清理服务。
 */
@Service
public class SystemCleanupServiceImpl implements SystemCleanupService {

    private final MongoTemplate mongoTemplate;
    private final MilvusVectorStore milvusVectorStore;
    private final SearchIndexService searchIndexService;

    /** 初始化系统数据清理服务。 */
    public SystemCleanupServiceImpl(
            MongoTemplate mongoTemplate,
            MilvusVectorStore milvusVectorStore,
            SearchIndexService searchIndexService) {
        this.mongoTemplate = mongoTemplate;
        this.milvusVectorStore = milvusVectorStore;
        this.searchIndexService = searchIndexService;
    }

    /**
     * 清空全部业务数据，同时保留数据结构。
     */
    @Override
    public void clearAll() {
        // 1. 清空 Milvus 向量数据
        milvusVectorStore.clearAll();

        // 2. 清空 Elasticsearch 索引数据
        searchIndexService.clearAll();

        // 3. 清空 MongoDB 数据
        clearMongo();
    }

    /**
     * 清空MongoDB所有Collection数据，但保留Collection和索引。
     */
    private void clearMongo() {
        for (String collectionName : mongoTemplate.getCollectionNames()) {
            if (collectionName.startsWith("system.")) {
                continue;
            }

            mongoTemplate.getCollection(collectionName)
                    .deleteMany(new Document());
        }
    }
}