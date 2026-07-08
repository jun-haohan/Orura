package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.domain.KnowledgeDocument;
import com.junhaohan.knowledgeingestion.dto.DocumentUploadResponse;
import com.junhaohan.knowledgeingestion.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentController(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    /**
     * ping，检查服务是否正常启动
     */
    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    public DocumentUploadResponse upload(@RequestParam("file") MultipartFile file) throws Exception {
        return documentIngestionService.upload(file);
    }

    /**
     * 查询文件列表
     */
    @GetMapping
    public List<KnowledgeDocument> list() {
        return documentIngestionService.listDocuments();
    }

    /**
     * 查询指定id文件
     */
    @GetMapping("/{id}")
    public KnowledgeDocument get(@PathVariable String id) {
        return documentIngestionService.getDocument(id);
    }

    /**
     * 查询指定id文件的所有切块
     */
    @GetMapping("/{id}/chunks")
    public List<DocumentChunk> chunks(@PathVariable String id) {
        return documentIngestionService.listChunks(id);
    }

    /**
     * 删除指定id文件
     */
    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        documentIngestionService.deleteDocument(id);
        return "delete ok";
    }
}