package com.junhaohan.knowledgeingestion.service;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.dto.DocumentUploadResponse;
import com.junhaohan.knowledgeingestion.embedding.event.DocumentEmbeddingEvent;
import com.junhaohan.knowledgeingestion.enums.EmbeddingStatus;
import com.junhaohan.knowledgeingestion.infrastructure.milvus.MilvusVectorStore;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import com.junhaohan.knowledgeingestion.retrieval.search.ElasticsearchSearchIndexService;
import com.junhaohan.knowledgeingestion.service.parser.DocumentParser;
import com.junhaohan.knowledgeingestion.service.parser.ParseResult;
import com.junhaohan.knowledgeingestion.service.splitter.MarkdownChunkSplitter;
import com.junhaohan.knowledgeingestion.service.splitter.RecursiveTextSplitter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private final List<DocumentParser> parsers;
    private final MarkdownChunkSplitter markdownChunkSplitter;
    private final RecursiveTextSplitter recursiveTextSplitter;
    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ElasticsearchSearchIndexService elasticsearchSearchIndexService;

    public DocumentIngestionService(
            List<DocumentParser> parsers,
            MarkdownChunkSplitter markdownChunkSplitter,
            RecursiveTextSplitter recursiveTextSplitter,
            KnowledgeDocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            ElasticsearchSearchIndexService elasticsearchSearchIndexService,
            ObjectMapper objectMapper,
            MilvusVectorStore milvusVectorStore,
            ApplicationEventPublisher eventPublisher
    ) {
        this.parsers = parsers;
        this.markdownChunkSplitter = markdownChunkSplitter;
        this.recursiveTextSplitter = recursiveTextSplitter;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.elasticsearchSearchIndexService = elasticsearchSearchIndexService;
        this.objectMapper = objectMapper;
        this.milvusVectorStore = milvusVectorStore;
        this.eventPublisher = eventPublisher;
    }

    private static final String STORAGE_DIR = "data/uploads";

    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;

    private final ObjectMapper objectMapper;

    private final MilvusVectorStore milvusVectorStore;

    private final ApplicationEventPublisher eventPublisher;

    private List<String> split(ParseResult result) {
        return switch (result.format().toLowerCase()) {
            case "markdown" ->
                    markdownChunkSplitter.split(result.content());

            case "txt" ->
                    recursiveTextSplitter.split(result.content());

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported content format: " + result.format()
                    );
        };
    }

    /**
     * 文件格式过滤
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cant be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size more than 15MB");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cant be empty");
        }

        String fileType = getFileType(filename);
        if (!List.of("txt", "md", "markdown", "pdf", "doc", "docx").contains(fileType)) {
            System.out.println(fileType);
            throw new IllegalArgumentException("File type not supported: " + fileType);
        }
    }

    public DocumentUploadResponse upload(MultipartFile file) throws Exception {

        validateFile(file);

        String originalFilename = file.getOriginalFilename();

        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        String fileType = getFileType(originalFilename);

        findParser(fileType);

        Files.createDirectories(Path.of(STORAGE_DIR));

        String savedFileName = UUID.randomUUID() + "_" + originalFilename;
        Path savedPath = Path.of(STORAGE_DIR, savedFileName);

        file.transferTo(savedPath);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setFileName(originalFilename);
        document.setFileType(fileType);
        document.setStoragePath(savedPath.toString());
        document.setStatus("PARSING");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());

        document = documentRepository.save(document);

        try {
            return parseAndStoreDocument(document, savedPath, false);
        } catch (Exception e) {
            markParseFailed(document, e);
        }

        return new DocumentUploadResponse(document.getId(), -1);
    }

    public DocumentUploadResponse retry(String documentId) throws Exception {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));

        markParsing(document);

        try {
            Path path = Path.of(document.getStoragePath());
            return parseAndStoreDocument(document, path, true);
        } catch (Exception e) {
            markParseFailed(document, e);
            throw e;
        }
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
        milvusVectorStore.deleteByDocumentId(documentId);
        elasticsearchSearchIndexService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
    }

    private DocumentUploadResponse parseAndStoreDocument(
            KnowledgeDocument document,
            Path path,
            boolean replaceExistingData
    ) throws Exception {
        DocumentParser parser = findParser(document.getFileType());
        ParseResult parseResult = parser.parse(path);

        document.setParser(parseResult.parser());
        document.setContentFormat(parseResult.format());
        document.setContentLength(parseResult.content().length());

        List<String> chunks = split(parseResult);

        if (chunks.isEmpty()) {
            throw new IllegalStateException(
                    "No chunks generated from parsed document: " + document.getId()
            );
        }

        if (replaceExistingData) {
            chunkRepository.deleteByDocumentId(document.getId());
            milvusVectorStore.deleteByDocumentId(document.getId());
            document.setEmbeddingStatus(EmbeddingStatus.NOT_READY);
            document.setEmbeddingErrorMessage(null);
            document.setEmbeddedAt(null);
        }

        saveChunks(document.getId(), chunks);
        markParseSuccess(document, chunks.size());

        eventPublisher.publishEvent(
                new DocumentEmbeddingEvent(document.getId())
        );

        return new DocumentUploadResponse(document.getId(), chunks.size());
    }

    private DocumentParser findParser(String fileType) {
        return parsers.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("Unsupported file type: " + fileType));
    }

    private void saveChunks(String documentId, List<String> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setCharCount(chunks.get(i).length());
            chunk.setCreatedAt(LocalDateTime.now());
            chunkRepository.save(chunk);
        }
    }

    private void markParsing(KnowledgeDocument document) {
        document.setStatus("PARSING");
        document.setErrorMessage(null);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    private void markParseSuccess(KnowledgeDocument document, int chunkCount) {
        document.setStatus("SUCCESS");
        document.setChunkCount(chunkCount);
        document.setUpdatedAt(LocalDateTime.now());
        document.setEmbeddingStatus(EmbeddingStatus.PENDING);
        document.setEmbeddingErrorMessage(null);
        document.setEmbeddedAt(null);
        documentRepository.save(document);
    }

    private void markParseFailed(KnowledgeDocument document, Exception e) {
        document.setStatus("FAILED");
        document.setErrorMessage(e.getMessage());
        document.setUpdatedAt(LocalDateTime.now());
        document.setEmbeddingStatus(EmbeddingStatus.NOT_READY);
        documentRepository.save(document);
    }

    private String[] extractTitleAndContent(String text) {
        if (text == null || text.isBlank()) {
            return new String[]{"", ""};
        }

        String[] lines = text.split("\\R", 2);

        if (lines[0].matches("^#{1,6}\\s+.+$")) {
            return new String[]{
                    lines[0],
                    lines.length > 1 ? lines[1].trim() : ""
            };
        }

        return new String[]{"", text};
    }

    public void exportChunks(String documentId) throws IOException {
        List<DocumentChunk> chunks =
                chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);

        Path output = Paths.get("tmp_output.txt");

        try (BufferedWriter writer = Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (DocumentChunk chunk : chunks) {
                String[] parts = extractTitleAndContent(chunk.getContent());

                writer.write("{");
                writer.newLine();

                writer.write("\"title\": "
                        + objectMapper.writeValueAsString(parts[0]) + ",");
                writer.newLine();

                writer.write("\"content\": "
                        + objectMapper.writeValueAsString(parts[1]));
                writer.newLine();

                writer.write("}");
                writer.newLine();
                writer.newLine();
            }
        }
    }
}
