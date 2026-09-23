package com.junhaohan.knowledgeingestion.infrastructure.milvus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.junhaohan.knowledgeingestion.config.MilvusProperties;
import com.junhaohan.knowledgeingestion.embedding.dto.ChunkVector;
import com.junhaohan.knowledgeingestion.retrieval.dto.VectorSearchHit;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
    向量库操作 CRUD
 */
@Component
@RequiredArgsConstructor
public class MilvusVectorStore {

    private final MilvusClientV2 client;
    private final MilvusProperties properties;

    public void deleteByDocumentId(String documentId) {
        client.delete(
                DeleteReq.builder()
                        .collectionName(properties.getCollectionName())
                        .filter("document_id == \"" + documentId + "\"")
                        .build()
        );
    }

    public void insert(List<ChunkVector> vectors) {

        List<JsonObject> rows = vectors.stream()
                .map(this::toJson)
                .toList();

        client.insert(
                InsertReq.builder()
                        .collectionName(properties.getCollectionName())
                        .data(rows)
                        .build()
        );
    }

    /**
     * 将 ChunkVector 转换为 Milvus 行数据。
     */
    private JsonObject toJson(ChunkVector item) {
        JsonObject row = new JsonObject();

        row.addProperty("chunk_id", item.getChunkId());
        row.addProperty("document_id", item.getDocumentId());
        row.addProperty("chunk_index", item.getChunkIndex());

        JsonArray embedding = new JsonArray();
        item.getEmbedding().forEach(embedding::add);

        row.add("embedding", embedding);

        return row;
    }

    /**
     * 统计指定文档在 Milvus 中的向量数量。
     */
    public long countByDocumentId(String documentId) {

        QueryResp response = client.query(
                QueryReq.builder()
                        .collectionName(properties.getCollectionName())
                        .filter("document_id == \"" + documentId + "\"")
                        .outputFields(List.of("count(*)"))
                        .build()
        );

        if (response.getQueryResults().isEmpty()) {
            return 0;
        }

        Object value = response.getQueryResults()
                .get(0)
                .getEntity()
                .get("count(*)");

        if (value instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(value.toString());
    }

    /**
     * 根据查询向量执行 TopK 相似度检索。
     */
    public List<VectorSearchHit> search(
            List<Float> queryVector,
            List<String> documentIds,
            int topK) {

        String documentIdsFilter = buildDocumentFilter(documentIds);

        SearchResp response = client.search(
                SearchReq.builder()
                        .collectionName(properties.getCollectionName())
                        .annsField("embedding")
                        .data(List.of(new FloatVec(queryVector)))
                        .topK(topK)
                        .filter(documentIdsFilter)
                        .outputFields(List.of(
                                "document_id",
                                "chunk_index"
                        ))
                        .build()
        );

        if (response.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<SearchResp.SearchResult> results =
                response.getSearchResults().get(0);

        return results.stream()
                .map(this::toVectorSearchHit)
                .toList();
    }

    /**
     * 将 Milvus 检索结果转换为业务 DTO。
     */
    private VectorSearchHit toVectorSearchHit(
            SearchResp.SearchResult result) {

        Map<String, Object> entity = result.getEntity();

        String chunkId = String.valueOf(result.getId());

        String documentId =
                String.valueOf(entity.get("document_id"));

        Integer chunkIndex =
                ((Number) entity.get("chunk_index")).intValue();

        return new VectorSearchHit(
                chunkId,
                documentId,
                chunkIndex,
                result.getScore()
        );
    }

    /**
     * 构造文档范围过滤表达式。
     */
    private String buildDocumentFilter(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return "";
        }

        String values = documentIds.stream()
                .map(this::escapeMilvusString)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));

        return "document_id in [" + values + "]";
    }

    /**
     * 转义 Milvus 字符串值。
     */
    private String escapeMilvusString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * 清空全部向量数据，但保留 Collection、Schema 和索引。
     */
    public void clearAll() {
        client.delete(
                DeleteReq.builder()
                        .collectionName(properties.getCollectionName())
                        .filter("chunk_id != \"\"")
                        .build()
        );
    }
}