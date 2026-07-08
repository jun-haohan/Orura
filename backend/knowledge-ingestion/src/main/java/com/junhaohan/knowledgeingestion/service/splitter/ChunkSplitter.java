package com.junhaohan.knowledgeingestion.service.splitter;

import java.util.List;


/**
 * 文本切块器接口
 */
public interface ChunkSplitter {

    List<String> split(String text);
}