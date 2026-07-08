package com.junhaohan.knowledgeingestion.service.splitter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


/**
 * 文本切块器
 */
@Component
public class RecursiveTextSplitter implements ChunkSplitter {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 200;

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            chunks.add(text.substring(start, end));

            if (end == length) {
                break;
            }

            start = end - OVERLAP;
        }

        return chunks;
    }
}