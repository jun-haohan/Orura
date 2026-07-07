package com.junhaohan.knowledgeingestion.service.parser;

import java.nio.file.Path;

public interface DocumentParser {

    boolean supports(String fileType);

    String parse(Path path) throws Exception;
}