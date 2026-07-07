package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.dto.DocumentUploadResponse;
import com.junhaohan.knowledgeingestion.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentController(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @PostMapping("/upload")
    public DocumentUploadResponse upload(@RequestParam("file") MultipartFile file) throws Exception {
        return documentIngestionService.upload(file);
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }
}