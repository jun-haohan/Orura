package com.junhaohan.knowledgeingestion.service.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * txt文件解析器
 */
@Component
public class TxtParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public ParseResult parse(Path path) throws Exception {
        String content = Files.readString(path);
        return new ParseResult(
                content,
                "txt",
                "builtin-text"
        );
    }
}