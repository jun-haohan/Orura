package com.junhaohan.knowledgeingestion.service.splitter;

import java.util.List;

public interface ChunkSplitter {

    List<String> split(String text);
}