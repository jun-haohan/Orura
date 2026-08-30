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
        System.out.println("ok support");
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public ParseResult parse(Path path) throws Exception {
        System.out.println("ok parse");
        String content = Files.readString(path);
        System.out.println(content);
        return new ParseResult(
                content,
                "txt",
                "builtin-text"
        );
    }
}