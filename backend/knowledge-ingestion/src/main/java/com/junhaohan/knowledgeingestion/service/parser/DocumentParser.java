package com.junhaohan.knowledgeingestion.service.parser;

import java.nio.file.Path;


/**
 * 文档解析器接口
 */
public interface DocumentParser {

    boolean supports(String fileType);

    ParseResult parse(Path path) throws Exception;
}