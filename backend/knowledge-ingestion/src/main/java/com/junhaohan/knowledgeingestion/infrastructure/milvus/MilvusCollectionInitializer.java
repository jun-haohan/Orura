package com.junhaohan.knowledgeingestion.infrastructure.milvus;

import com.junhaohan.knowledgeingestion.config.EmbeddingProperties;
import com.junhaohan.knowledgeingestion.config.MilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 项目启动时检查collection是否存在，不存在时创建
@Component
@RequiredArgsConstructor
@Slf4j
public class MilvusCollectionInitializer implements ApplicationRunner {
    private static final String CHUNK_ID = "chunk_id";
    private static final String DOCUMENT_ID = "document_id";
    private static final String CHUNK_INDEX = "chunk_index";
    private static final String EMBEDDING = "embedding";

    private final MilvusClientV2 client;
    private final MilvusProperties milvusProperties;
    private final EmbeddingProperties embeddingProperties;

    @Override
    public void run(ApplicationArguments args) {

        String collectionName = milvusProperties.getCollectionName();

        Boolean exists = client.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(collectionName)
                        .build()
        );

        if (exists) {
            log.info("Milvus collection already exists: {}", collectionName);
            describeCollection(collectionName);
            return;
        }

        /*
        knowledge_chunk_vector

        chunk_id        VARCHAR PRIMARY KEY
        document_id     VARCHAR
        chunk_index     INT64
        embedding       FLOAT_VECTOR(1024)
         */

        // 创建schema
        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("document_id")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_index")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(embeddingProperties.getDimension())
                .build());

        // 创建HNSW索引
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexName("embedding_hnsw_idx")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of(
                        "M", 16,
                        "efConstruction", 200
                ))
                .build();

        // 创建Collection
        client.createCollection(
                CreateCollectionReq.builder()
                        .collectionName(collectionName)
                        .collectionSchema(schema)
                        .indexParams(List.of(vectorIndex))
                        .build()
        );

        log.info("Milvus collection created: {}", collectionName);

        describeCollection(collectionName);
    }

    // 验证创建成功
    private void describeCollection(String collectionName) {
        DescribeCollectionResp resp = client.describeCollection(
                DescribeCollectionReq.builder()
                        .collectionName(collectionName)
                        .build()
        );

        log.info("Milvus collection: {}", resp.getCollectionName());
        log.info("Primary field: {}", resp.getPrimaryFieldName());
        log.info("Vector field: {}", resp.getVectorFieldNames());
        log.info("Fields: {}", resp.getFieldNames());
    }
}
