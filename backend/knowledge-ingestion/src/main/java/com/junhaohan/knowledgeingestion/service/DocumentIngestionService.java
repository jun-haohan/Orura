package com.junhaohan.knowledgeingestion.service;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.dto.DocumentUploadResponse;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import com.junhaohan.knowledgeingestion.service.parser.DocumentParser;
import com.junhaohan.knowledgeingestion.service.splitter.ChunkSplitter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private final List<DocumentParser> parsers;
    private final ChunkSplitter chunkSplitter;
    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    public DocumentIngestionService(
            List<DocumentParser> parsers,
            ChunkSplitter chunkSplitter,
            KnowledgeDocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository
    ) {
        this.parsers = parsers;
        this.chunkSplitter = chunkSplitter;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    private static final String STORAGE_DIR = "data/uploads";

    public DocumentUploadResponse upload(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();

        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        String fileType = getFileType(originalFilename);

        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported file type:" + fileType));

        Files.createDirectories(Path.of(STORAGE_DIR));

        String savedFileName = UUID.randomUUID() + "_" + originalFilename;
        Path savedPath = Path.of(STORAGE_DIR, savedFileName);

        file.transferTo(savedPath);

        String text = parser.parse(savedPath);
        List<String> chunks = chunkSplitter.split(text);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setFileName(originalFilename);
        document.setFileType(fileType);
        document.setStoragePath(savedPath.toString());
        document.setChunkCount(chunks.size());
        document.setCreatedAt(LocalDateTime.now());

        KnowledgeDocument savedDocument = documentRepository.save(document);

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(savedDocument.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setCharCount(chunks.get(i).length());
            chunk.setCreatedAt(LocalDateTime.now());

            chunkRepository.save(chunk);
        }

        return new DocumentUploadResponse(savedDocument.getId(), chunks.size());
    }

    private String getFileType(String filename) {
        int index = filename.lastIndexOf(".");
        if (index < 0) {
            return "";
        }
        return filename.substring(index + 1).toLowerCase();
    }

    public List<KnowledgeDocument> listDocuments() {
        return documentRepository.findAll();
    }

    public KnowledgeDocument getDocument(String id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
    }

    public List<DocumentChunk> listChunks(String documentId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
    }

    public void deleteDocument(String documentId) {
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
    }
}