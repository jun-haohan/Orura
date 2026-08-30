package com.junhaohan.knowledgeingestion.service.parser;

import com.junhaohan.knowledgeingestion.client.ParserServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class PdfParser implements DocumentParser {

    private final ParserServiceClient parserServiceClient;

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    @Override
    public ParseResult parse(Path path) throws Exception {
        String content = parserServiceClient.parse(path);
        return new ParseResult(
                content,
                "markdown",
                "docling"
        );
    }
}