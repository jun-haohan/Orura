package com.junhaohan.knowledgeingestion.service.parser;

public record ParseResult (
        String content,
        String format,
        String parser
) {}
