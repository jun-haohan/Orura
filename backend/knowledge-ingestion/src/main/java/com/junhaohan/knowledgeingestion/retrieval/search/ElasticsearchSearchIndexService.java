package com.junhaohan.knowledgeingestion.retrieval.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.retrieval.search.model.KnowledgeChunkIndex;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexHit;
import com.junhaohan.knowledgeingestion.retrieval.search.model.SearchIndexRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ElasticsearchSearchIndexService implements SearchIndexService {

    private static final IndexCoordinates INDEX =
            IndexCoordinates.of("knowledge_chunk");

    private final ElasticsearchOperations elasticsearchOperations;
    private final DocumentChunkRepository documentChunkRepository;

    /** 初始化 Elasticsearch 索引服务。 */
    public ElasticsearchSearchIndexService(
            ElasticsearchOperations elasticsearchOperations,
            DocumentChunkRepository documentChunkRepository) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.documentChunkRepository = documentChunkRepository;
    }

    /** 将指定文档的所有 Chunk 批量写入 Elasticsearch。 */
    @Override
    public void indexDocument(String documentId) {
        List<DocumentChunk> chunks =
                documentChunkRepository.findByDocumentId(documentId);

        if (chunks.isEmpty()) {
            return;
        }

        List<IndexQuery> queries = chunks.stream()
                .map(this::buildIndexQuery)
                .toList();

        elasticsearchOperations.bulkIndex(queries, INDEX);
    }

    /** 构造 Elasticsearch 索引请求。 */
    private IndexQuery buildIndexQuery(DocumentChunk chunk) {
        KnowledgeChunkIndex document = new KnowledgeChunkIndex();

        document.setChunkId(chunk.getId());
        document.setDocumentId(chunk.getDocumentId());
        document.setChunkIndex(chunk.getChunkIndex());
        document.setContent(chunk.getContent());

        return new IndexQueryBuilder()
                .withId(chunk.getId())
                .withObject(document)
                .build();
    }

    /** 删除文档对应的Elasticsearch索引。 */
    @Override
    public void deleteByDocumentId(String documentId) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.term(t -> t
                        .field("document_id")
                        .value(documentId)
                ))
                .build();

        DeleteQuery deleteQuery = DeleteQuery.builder(query)
                .withRefresh(true)
                .build();

        elasticsearchOperations.delete(
                deleteQuery,
                KnowledgeChunkIndex.class,
                INDEX
        );
    }

    /**
     * 执行 BM25 关键词检索。
     */
    @Override
    public List<SearchIndexHit> search(SearchIndexRequest request) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.match(match -> match
                            .field("content")
                            .query(request.query())
                    ));

                    if (request.documentIds() != null
                            && !request.documentIds().isEmpty()) {

                        List<FieldValue> values = request.documentIds().stream()
                                .map(FieldValue::of)
                                .toList();

                        b.filter(f -> f.terms(t -> t
                                .field("document_id")
                                .terms(v -> v.value(values))
                        ));
                    }

                    return b;
                }))
                .withPageable(PageRequest.of(0, request.topK()))
                .build();

        SearchHits<KnowledgeChunkIndex> hits =
                elasticsearchOperations.search(
                        query,
                        KnowledgeChunkIndex.class,
                        INDEX
                );

        return hits.getSearchHits().stream()
                .map(this::toSearchIndexHit)
                .toList();
    }

    /**
     * 将 Elasticsearch 命中结果转换为统一索引结果。
     */
    private SearchIndexHit toSearchIndexHit(
            SearchHit<KnowledgeChunkIndex> hit) {

        KnowledgeChunkIndex source = hit.getContent();

        return new SearchIndexHit(
                source.getChunkId(),
                source.getDocumentId(),
                hit.getScore()
        );
    }

    /**
     * 统计指定文档在 Elasticsearch 中的 Chunk 数量。
     */
    @Override
    public long countByDocumentId(String documentId) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.term(t -> t
                        .field("document_id")
                        .value(documentId)
                ))
                .build();

        return elasticsearchOperations.count(
                query,
                KnowledgeChunkIndex.class,
                INDEX
        );
    }

    /**
     * 清空全部 Elasticsearch Chunk 数据，但保留索引结构。
     */
    @Override
    public void clearAll() {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .build();

        DeleteQuery deleteQuery = DeleteQuery.builder(query)
                .withRefresh(true)
                .build();

        elasticsearchOperations.delete(
                deleteQuery,
                KnowledgeChunkIndex.class,
                INDEX
        );
    }
}
