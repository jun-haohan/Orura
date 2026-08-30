package com.junhaohan.knowledgeingestion.service.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
    public ParseResult parse(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return new ParseResult(
                content,
                "markdown",
                "builtin-markdown"
        );
    }
}