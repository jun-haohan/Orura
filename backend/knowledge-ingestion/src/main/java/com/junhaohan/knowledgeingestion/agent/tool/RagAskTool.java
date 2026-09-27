package com.junhaohan.knowledgeingestion.agent.tool;

import com.junhaohan.knowledgeingestion.agent.config.AgentProperties;
import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import com.junhaohan.knowledgeingestion.domain.DocumentChunk;
import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.rag.model.RagRequest;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.model.RagStatus;
import com.junhaohan.knowledgeingestion.rag.prompt.RagPromptBuilder;
import com.junhaohan.knowledgeingestion.rag.service.RagService;
import com.junhaohan.knowledgeingestion.repository.DocumentChunkRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 复用已有 RAG 问答，并锁定本次 Agent 请求的文档范围。 */
@Component
public class RagAskTool implements AgentTool {

    private final RagService ragService;
    private final RagPromptBuilder promptBuilder;
    private final DocumentChunkRepository chunkRepository;
    private final AgentProperties properties;

    /** 注入 RAG 服务、引用原文来源及工具结果预算。 */
    public RagAskTool(RagService ragService, RagPromptBuilder promptBuilder,
                      DocumentChunkRepository chunkRepository, AgentProperties properties) {
        this.ragService = ragService;
        this.promptBuilder = promptBuilder;
        this.chunkRepository = chunkRepository;
        this.properties = properties;
    }

    /** 返回知识工具的固定名称。 */
    @Override
    public String name() {
        return "rag_ask";
    }

    /** 告诉模型何时应查询知识库。 */
    @Override
    public String description() {
        return "查询已上传文档中的事实，返回知识回答、引用和已引用的原文证据";
    }

    /** 只允许模型提供问题，不允许修改文档过滤范围。 */
    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("question", Map.of("type", "string", "description", "需要查询的知识问题")),
                "required", List.of("question"),
                "additionalProperties", false
        );
    }

    /** 校验问题字段，并复用 RAG 的问题长度限制。 */
    @Override
    public void validateArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1
                || arguments.get("question") == null || !arguments.get("question").isTextual()) {
            throw new IllegalArgumentException("rag_ask 只接受非空字符串 question");
        }
        promptBuilder.validateQuestion(arguments.get("question").asString());
    }

    /** 在本进程中调用 RAG，保留状态和来源可核验的引用。 */
    @Override
    public ToolResult execute(JsonNode arguments, AgentContext context) {
        validateArguments(arguments);
        String question = arguments.get("question").asString().strip();
        RagResponse response = ragService.ask(new RagRequest(
                question, context.documentIds(), null, null, null
        ));
        if (response.status() == RagStatus.INSUFFICIENT_EVIDENCE) {
            return new ToolResult(ToolResult.ToolStatus.INSUFFICIENT_EVIDENCE,
                    response.answer(), List.of());
        }
        if (response.status() != RagStatus.ANSWERED
                || response.answer() == null || response.answer().isBlank()
                || response.citations() == null || response.citations().isEmpty()) {
            throw new IllegalStateException("RAG 回答缺少有效状态或引用");
        }
        for (RagCitation citation : response.citations()) {
            if (citation == null || citation.documentId() == null || citation.documentId().isBlank()
                    || citation.chunkId() == null || citation.chunkId().isBlank()
                    || (context.documentIds() != null
                    && !context.documentIds().contains(citation.documentId()))) {
                throw new IllegalStateException("RAG 引用超出本次文档范围或来源无效");
            }
        }
        return new ToolResult(ToolResult.ToolStatus.SUCCEEDED,
                answerWithEvidence(response), response.citations());
    }

    /** 将真实引用的 MongoDB 原文有限加入结果，避免摘要遗漏指标。 */
    private String answerWithEvidence(RagResponse response) {
        int maxChars = properties.maxToolResultChars();
        String answer = response.answer().strip();
        StringBuilder content = new StringBuilder(answer.substring(0, Math.min(answer.length(), maxChars / 2)));
        for (RagCitation citation : response.citations()) {
            DocumentChunk chunk = chunkRepository.findById(citation.chunkId())
                    .orElseThrow(() -> new IllegalStateException("RAG 引用的 Chunk 不存在"));
            if (!citation.documentId().equals(chunk.getDocumentId())
                    || chunk.getContent() == null || chunk.getContent().isBlank()) {
                throw new IllegalStateException("RAG 引用与 Chunk 原文不一致");
            }
            String prefix = "\n原文证据 [" + citation.ref() + "]：";
            int remaining = maxChars - content.length() - prefix.length();
            if (remaining <= 0) {
                break;
            }
            String excerpt = chunk.getContent().strip();
            int length = Math.min(excerpt.length(), Math.min(1200, remaining));
            // 原文中的方括号数字是文档内容，不应被 CitationCollector 当作引用编号。
            content.append(prefix).append(excerpt.substring(0, length)
                    .replace('[', '〔').replace(']', '〕'));
        }
        return content.toString();
    }
}
