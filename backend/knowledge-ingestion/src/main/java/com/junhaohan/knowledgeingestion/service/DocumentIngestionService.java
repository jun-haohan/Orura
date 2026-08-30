package com.junhaohan.knowledgeingestion.service;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.dto.DocumentUploadResponse;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import com.junhaohan.knowledgeingestion.repository.KnowledgeDocumentRepository;
import com.junhaohan.knowledgeingestion.service.parser.DocumentParser;
import com.junhaohan.knowledgeingestion.service.parser.ParseResult;
import com.junhaohan.knowledgeingestion.service.splitter.ChunkSplitter;
import com.junhaohan.knowledgeingestion.service.splitter.MarkdownChunkSplitter;
import com.junhaohan.knowledgeingestion.service.splitter.MarkdownHeaderSplitter;
import com.junhaohan.knowledgeingestion.service.splitter.RecursiveTextSplitter;
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

    public DocumentIngestionService(
            List<DocumentParser> parsers,
            MarkdownChunkSplitter markdownChunkSplitter,
            RecursiveTextSplitter recursiveTextSplitter,
            KnowledgeDocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository, ObjectMapper objectMapper
    ) {
        this.parsers = parsers;
        this.markdownChunkSplitter = markdownChunkSplitter;
        this.recursiveTextSplitter = recursiveTextSplitter;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.objectMapper = objectMapper;
    }

    private static final String STORAGE_DIR = "data/uploads";

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final ObjectMapper objectMapper;

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

    // 上传验证
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cant be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size more than 10MB");
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
        System.out.println("validate success");
    }

    public DocumentUploadResponse upload(MultipartFile file) throws Exception {

        validateFile(file);

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

        KnowledgeDocument document = new KnowledgeDocument();
        document.setFileName(originalFilename);
        document.setFileType(fileType);
        document.setStoragePath(savedPath.toString());
        document.setStatus("PARSING");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());

        document = documentRepository.save(document);

        Integer chunkSize = -1;
        // 解析切块，同时更新解析进度，处理异常
        try {
            ParseResult parseResult = parser.parse(savedPath);

            System.out.println("ok 1");

            document.setParser(parseResult.parser());
            document.setContentFormat(parseResult.format());
            document.setContentLength(parseResult.content().length());

            System.out.println(parseResult);

            List<String> chunks = split(parseResult);
            chunkSize = chunks.size();

            System.out.println("ok 2");


            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setCharCount(chunks.get(i).length());
                chunk.setCreatedAt(LocalDateTime.now());
                chunkRepository.save(chunk);
            }
            System.out.println("ok 3");

            document.setStatus("SUCCESS");
            document.setChunkCount(chunks.size());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);

            return new DocumentUploadResponse(document.getId(), chunks.size());
        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);
        }

        return new DocumentUploadResponse(document.getId(), chunkSize);
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

    public DocumentUploadResponse retry(String documentId) throws Exception {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));

        document.setStatus("PARSING");
        document.setErrorMessage(null);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);

        try {
            Path path = Path.of(document.getStoragePath());

            DocumentParser parser = parsers.stream()
                    .filter(p -> p.supports(document.getFileType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("暂不支持该文件类型: " + document.getFileType()));

            ParseResult parseResult = parser.parse(path);

            document.setParser(parseResult.parser());
            document.setContentFormat(parseResult.format());
            document.setContentLength(parseResult.content().length());

            List<String> chunks = split(parseResult);

            chunkRepository.deleteByDocumentId(documentId);

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setCharCount(chunks.get(i).length());
                chunk.setCreatedAt(LocalDateTime.now());
                chunkRepository.save(chunk);
            }

            document.setStatus("SUCCESS");
            document.setChunkCount(chunks.size());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);

            return new DocumentUploadResponse(documentId, chunks.size());

        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);
            throw e;
        }
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