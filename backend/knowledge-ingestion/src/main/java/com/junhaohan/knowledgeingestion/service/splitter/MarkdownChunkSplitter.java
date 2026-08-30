package com.junhaohan.knowledgeingestion.service.splitter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MarkdownChunkSplitter implements ChunkSplitter{
    // markdown split，先按照markdown标题切分，再将大于1000字的部分按照长度切分
    private final MarkdownHeaderSplitter markdownHeaderSplitter;
    private final RecursiveTextSplitter recursiveTextSplitter;

    private static final int MAX_CHUNK_SIZE = 1000;

    @Override
    public List<String> split(String text) {
        List<MarkdownSection> sections = markdownHeaderSplitter.splitSections(text);
        List<String> result = new ArrayList<>();

        for (MarkdownSection section : sections) {
            String fullText = section.toMarkdown();

            if (fullText.length() <= MAX_CHUNK_SIZE) {
                result.add(fullText);
            } else {
                // 调用长度切分器进一步切割content，并为每一块都添加标题
                List<String> chunks = recursiveTextSplitter.split(section.content());
                for (String chunk : chunks) {
                    result.add(withHeader(section.header(), chunk));
                }
            }
        }

        return result;
    }

    private String withHeader(String header, String content) {
        if (header == null || header.isBlank()) {
            return content;
        }
        return header + "\n\n" + content;
    }
}
