package com.junhaohan.knowledgeingestion.service.splitter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkSplitterTest {

    private final MarkdownHeaderSplitter headerSplitter =
            new MarkdownHeaderSplitter();

    private final MarkdownChunkSplitter chunkSplitter =
            new MarkdownChunkSplitter(
                    headerSplitter,
                    new RecursiveTextSplitter()
            );

    @Test
    void retainsContentWhenMarkdownHasNoHeader() {
        String markdown = """
                附件 2 制造业中试平台重点方向建设要点（2025版）

                | 序号 | 行业 | 重点方向 |
                | --- | --- | --- |
                | 1 | 原材料工业 | 石化化工 |
                """;

        List<String> chunks = chunkSplitter.split(markdown);

        assertThat(chunks)
                .singleElement()
                .isEqualTo(markdown.trim());
    }

    @Test
    void returnsNoChunksForBlankMarkdown() {
        assertThat(chunkSplitter.split(" \n\t\n")).isEmpty();
    }

    @Test
    void recursivelySplitsLongContentWithoutMarkdownHeader() {
        String markdown = "表".repeat(22_273);

        List<String> chunks = chunkSplitter.split(markdown);

        assertThat(chunks).hasSize(28);
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.length())
                        .isBetween(1, 1_000));
    }
}
