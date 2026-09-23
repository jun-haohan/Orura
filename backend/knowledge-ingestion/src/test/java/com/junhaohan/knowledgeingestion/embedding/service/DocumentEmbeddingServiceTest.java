package com.junhaohan.knowledgeingestion.embedding.service;

import com.junhaohan.knowledgeingestion.config.EmbeddingProperties;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentEmbeddingServiceTest {

    @Test
    void marksEmbeddingAsFailedWhenDocumentHasNoChunks() {
        KnowledgeDocumentRepository documentRepository =
                mock(KnowledgeDocumentRepository.class);
        DocumentChunkRepository chunkRepository =
                mock(DocumentChunkRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusVectorStore milvusVectorStore = mock(MilvusVectorStore.class);

        DocumentEmbeddingService service = new DocumentEmbeddingService(
                documentRepository,
                chunkRepository,
                embeddingService,
                milvusVectorStore,
                new EmbeddingProperties()
        );

        KnowledgeDocument document = new KnowledgeDocument();
        document.setId("document-1");
        document.setStatus("SUCCESS");
        document.setEmbeddingStatus(EmbeddingStatus.PENDING);

        when(documentRepository.findById("document-1"))
                .thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("document-1"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.embedDocument("document-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document embedding failed: document-1")
                .hasRootCauseMessage("No chunks found for document: document-1");

        assertThat(document.getEmbeddingStatus())
                .isEqualTo(EmbeddingStatus.FAILED);
        assertThat(document.getEmbeddingErrorMessage())
                .isEqualTo("No chunks found for document: document-1");
        verify(documentRepository).findById("document-1");
        verify(chunkRepository)
                .findByDocumentIdOrderByChunkIndexAsc("document-1");
        verify(documentRepository, times(2)).save(document);
        verify(milvusVectorStore).deleteByDocumentId("document-1");
        verifyNoInteractions(embeddingService);
    }
}
