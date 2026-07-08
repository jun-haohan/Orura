package com.junhaohan.knowledgeingestion.service.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * markdown文件解析器
 */
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType)
                || "markdown".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(Path path) throws Exception {
        return Files.readString(path);
    }
}