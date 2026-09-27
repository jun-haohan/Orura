package com.junhaohan.knowledgeingestion.agent.citation;

import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将本次运行中不同 RAG 调用的引用统一编号并按来源去重。 */
public class CitationCollector {

    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d+)]");

    private final Map<Source, RagCitation> citations = new LinkedHashMap<>();

    /** 将一条工具结果的局部引用转换为本次运行的全局引用。 */
    public ToolResult collect(ToolResult result) {
        if (result.citations().isEmpty()) {
            return result;
        }

        Map<Source, RagCitation> updated = new LinkedHashMap<>(citations);
        Map<Integer, Integer> localToGlobal = new HashMap<>();
        for (RagCitation citation : result.citations()) {
            if (citation == null || citation.ref() < 1 || citation.documentId() == null
                    || citation.documentId().isBlank() || citation.chunkId() == null
                    || citation.chunkId().isBlank()) {
                throw new IllegalArgumentException("知识工具返回了无效引用");
            }
            Source source = new Source(citation.documentId(), citation.chunkId());
            RagCitation global = updated.get(source);
            if (global == null) {
                global = new RagCitation(updated.size() + 1,
                        citation.documentId(), citation.chunkId(), citation.snippet());
                updated.put(source, global);
            }
            Integer previous = localToGlobal.putIfAbsent(citation.ref(), global.ref());
            if (previous != null && previous != global.ref()) {
                throw new IllegalArgumentException("知识工具引用编号重复");
            }
        }

        Matcher matcher = REFERENCE.matcher(result.content());
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            final int localRef;
            try {
                localRef = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("知识工具引用编号无效", e);
            }
            Integer globalRef = localToGlobal.get(localRef);
            if (globalRef == null) {
                throw new IllegalArgumentException("知识工具回答引用了未返回的来源");
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("[" + globalRef + "]"));
        }
        matcher.appendTail(rewritten);
        citations.clear();
        citations.putAll(updated);
        List<RagCitation> mapped = result.citations().stream()
                .map(c -> updated.get(new Source(c.documentId(), c.chunkId())))
                .distinct()
                .toList();
        return new ToolResult(result.status(), rewritten.toString(), mapped);
    }

    /** 返回目前所有实际工具调用产生的引用，供最终回答校验。 */
    public List<RagCitation> available() {
        return List.copyOf(citations.values());
    }

    /** 以文档及 Chunk 标识唯一定位一个来源。 */
    private record Source(String documentId, String chunkId) {
    }
}