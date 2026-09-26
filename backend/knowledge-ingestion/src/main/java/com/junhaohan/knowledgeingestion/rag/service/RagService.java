package com.junhaohan.knowledgeingestion.rag.service;

import com.junhaohan.knowledgeingestion.rag.context.ContextBuilder;
import com.junhaohan.knowledgeingestion.rag.context.ContextBuilder.Context;
import com.junhaohan.knowledgeingestion.rag.llm.LlmClient;
import com.junhaohan.knowledgeingestion.rag.model.RagRequest;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.model.RagStatus;
import com.junhaohan.knowledgeingestion.rag.prompt.RagPromptBuilder;
import com.junhaohan.knowledgeingestion.rag.prompt.RagPromptBuilder.Prompts;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalFilter;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalMode;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalRequest;
import com.junhaohan.knowledgeingestion.retrieval.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** 编排单轮检索、证据构造、模型问答和引用校验。 */
@Slf4j
@Service
public class RagService {

    private static final int DEFAULT_TOP_K = 5;
    private static final String INSUFFICIENT_ANSWER = "无法根据提供的资料确认。";

    private final RetrievalService retrievalService;
    private final ContextBuilder contextBuilder;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final CitationValidator citationValidator;
    private final int maxTopK;
    private final int candidateTopK;

    /** 注入问答各环节，并校验检索数量配置。 */
    public RagService(
            RetrievalService retrievalService,
            ContextBuilder contextBuilder,
            RagPromptBuilder promptBuilder,
            LlmClient llmClient,
            CitationValidator citationValidator,
            @Value("${rag.retrieval.max-top-k:10}") int maxTopK,
            @Value("${rag.retrieval.candidate-top-k:30}") int candidateTopK) {
        if (maxTopK < DEFAULT_TOP_K || candidateTopK < maxTopK) {
            throw new IllegalArgumentException("RAG 检索数量配置不合法");
        }
        this.retrievalService = retrievalService;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.citationValidator = citationValidator;
        this.maxTopK = maxTopK;
        this.candidateTopK = candidateTopK;
    }

    /** 回答一次问题；没有可用证据或有效引用时返回证据不足。 */
    public RagResponse ask(RagRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String question = promptBuilder.validateQuestion(request.question());
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        if (topK < 1 || topK > maxTopK) {
            throw new IllegalArgumentException("topK 必须在 1 到 " + maxTopK + " 之间");
        }

        List<String> documentIds = normalizeDocumentIds(request.documentIds());
        RetrievalRequest retrievalRequest = new RetrievalRequest(
                question,
                request.mode() == null ? RetrievalMode.HYBRID : request.mode(),
                topK,
                candidateTopK,
                request.rerank(),
                documentIds == null ? null : new RetrievalFilter(documentIds)
        );
        Context context = contextBuilder.build(
                retrievalService.retrieve(retrievalRequest), documentIds
        );

        log.info("Citation: {}", context.citations());

        if (context.citations().isEmpty()) {
            return insufficientEvidence();
        }

        Prompts prompts = promptBuilder.build(question, context);
        String rawAnswer = llmClient.generate(prompts.systemPrompt(), prompts.userPrompt());
        CitationValidator.ValidatedAnswer result = citationValidator.validate(
                rawAnswer, context.citations()
        );
        if (result.citations().isEmpty()
                || result.answer().contains("无法根据提供的资料确认")) {
            return insufficientEvidence();
        }
        return new RagResponse(RagStatus.ANSWERED, result.answer(), result.citations());
    }

    /** 校验文档过滤条件；空列表与未指定一样表示不限制文档。 */
    private List<String> normalizeDocumentIds(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return null;
        }
        if (documentIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("documentIds 不能包含空文档 ID");
        }
        return documentIds.stream().map(String::strip).distinct().toList();
    }

    /** 构造无证据时的统一响应，避免调用模型。 */
    private RagResponse insufficientEvidence() {
        return new RagResponse(RagStatus.INSUFFICIENT_EVIDENCE, INSUFFICIENT_ANSWER, List.of());
    }
}