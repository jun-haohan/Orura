package com.junhaohan.knowledgeingestion.rag.context;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 将检索结果整理为有字符预算和引用编号的证据上下文。 */
@Slf4j
@Component
public class ContextBuilder {

    private static final int SNIPPET_CHARS = 160;

    private final int maxContextChars;
    private final int maxChunkChars;

    /** 设置证据总字符预算与单条 Chunk 的字符上限。 */
    public ContextBuilder(
            @Value("${rag.context.max-chars:4000}") int maxContextChars,
            @Value("${rag.context.max-chunk-chars:1200}") int maxChunkChars) {
        if (maxContextChars <= 0 || maxChunkChars <= 0) {
            throw new IllegalArgumentException("RAG 上下文字符预算必须大于 0");
        }
        this.maxContextChars = maxContextChars;
        this.maxChunkChars = maxChunkChars;
    }

    /** 按检索顺序过滤、去重、截取证据，并建立引用编号到 Chunk 的映射。 */
    public Context build(List<RetrievalResult> results, List<String> documentIds) {
        if (results == null || results.isEmpty()) {
            return new Context("", List.of());
        }

        // 即使检索端已应用过滤，仍在生成引用前核对请求允许的文档。
        Set<String> allowedIds = documentIds == null || documentIds.isEmpty()
                ? null : new HashSet<>(documentIds);
        Set<String> seenChunkIds = new HashSet<>();
        List<RagCitation> citations = new ArrayList<>();
        StringBuilder evidence = new StringBuilder();

        for (RetrievalResult result : results) {
            if (result == null || result.chunkId() == null || result.chunkId().isBlank()
                    || result.documentId() == null || result.documentId().isBlank()
                    || result.content() == null || result.content().isBlank()
                    || (allowedIds != null && !allowedIds.contains(result.documentId()))
                    || seenChunkIds.contains(result.chunkId())) {
                continue;
            }

            String content = result.content().strip();
            int ref = citations.size() + 1;
            String separator = evidence.isEmpty() ? "" : "\n\n";
            String prefix = "[" + ref + "]\n";
            int remaining = maxContextChars - evidence.length() - separator.length() - prefix.length();
            if (remaining <= 0) {
                break;
            }

            // 引用摘要只取实际发送给模型的文字，不能引用被截去的正文。
            String excerpt = content.substring(
                    0, Math.min(content.length(), Math.min(maxChunkChars, remaining))
            );
            evidence.append(separator).append(prefix).append(excerpt);
            citations.add(new RagCitation(
                    ref, result.documentId(), result.chunkId(),
                    excerpt.substring(0, Math.min(SNIPPET_CHARS, excerpt.length()))
            ));
            seenChunkIds.add(result.chunkId());
        }

        log.info("ChunkIds: {}", seenChunkIds);

        return new Context(evidence.toString(), List.copyOf(citations));
    }

    /** 本次发送给模型的证据及其服务端引用映射。 */
    public record Context(String evidence, List<RagCitation> citations) {
    }
}