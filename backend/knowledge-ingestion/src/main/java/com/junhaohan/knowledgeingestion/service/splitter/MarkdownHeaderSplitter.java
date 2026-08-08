package com.junhaohan.knowledgeingestion.service.splitter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MarkdownHeaderSplitter {

    public List<String> splitByHeader(String text) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        String[] lines = text.split("\\R");
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("#") && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }

            current.append(line).append("\n");
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }
}