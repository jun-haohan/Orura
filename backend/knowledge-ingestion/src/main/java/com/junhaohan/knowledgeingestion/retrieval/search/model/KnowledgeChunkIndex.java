package com.junhaohan.knowledgeingestion.retrieval.search.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Data
@Document(indexName = "knowledge_chunk", createIndex = false)
public class KnowledgeChunkIndex {

    @Id
    @Field(name = "chunk_id", type = FieldType.Keyword)
    private String chunkId;

    @Field(name = "document_id", type = FieldType.Keyword)
    private String documentId;

    @Field(name = "chunk_index", type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(name = "content", type = FieldType.Text)
    private String content;

    // getter / setter
}