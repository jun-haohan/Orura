package com.junhaohan.knowledgeingestion.service.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class WordParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "doc".equalsIgnoreCase(fileType)
                || "docx".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(Path path) {
        throw new UnsupportedOperationException("Word解析暂未实现");
    }
}