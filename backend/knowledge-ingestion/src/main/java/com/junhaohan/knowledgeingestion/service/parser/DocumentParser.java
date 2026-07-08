package com.junhaohan.knowledgeingestion.service.parser;

import java.nio.file.Path;


/**
 * 文档解析器接口
 */
public interface DocumentParser {

    boolean supports(String fileType);

    String parse(Path path) throws Exception;
}