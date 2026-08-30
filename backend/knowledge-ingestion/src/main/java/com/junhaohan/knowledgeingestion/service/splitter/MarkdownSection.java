package com.junhaohan.knowledgeingestion.service.splitter;

public record MarkdownSection (
        String header,
        String content
) {
    public String toMarkdown() {
        if (header == null || header.isBlank()) {
            return content;
        }

        if (content == null || content.isBlank()) {
            return header;
        }

        return header + "\n\n" + content;
    }
}
