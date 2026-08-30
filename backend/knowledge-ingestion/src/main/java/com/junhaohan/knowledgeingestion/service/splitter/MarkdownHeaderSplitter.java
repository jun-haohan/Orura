package com.junhaohan.knowledgeingestion.service.splitter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownHeaderSplitter implements ChunkSplitter{

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Override
    public List<String> split(String text) {
        return splitSections(text).stream().map(MarkdownSection::toMarkdown).toList();
    }

    // 逐行扫描 Markdown，每遇到一个标题，就把前面的内容封装成一个 MarkdownSection，遇到代码块则跳过
    public List<MarkdownSection> splitSections(String text) {
        List<MarkdownSection> sections = new ArrayList<>();

        String currentHeader = "";
        StringBuilder content = new StringBuilder();
        boolean inCodeBlock = false;

        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
            }

            Matcher matcher = HEADER_PATTERN.matcher(line);

            // 遇到新标题时，结束上一个section，保存，并记录该标题
            if (!inCodeBlock && matcher.matches()) {
                addSection(sections, currentHeader, content);
                currentHeader = line.trim();
                content.setLength(0);
            } else {
                content.append(line).append("\n");
            }
        }

        addSection(sections, currentHeader, content);
        return sections;
    }

    private void addSection(List<MarkdownSection> sections, String header, StringBuilder content) {
        String text = content.toString().trim();
        if (!header.isBlank() || text.isBlank()) {
            sections.add(new MarkdownSection(header, text));
        }
    }
}