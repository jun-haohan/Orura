package com.junhaohan.knowledgeingestion.rag.llm;

/** 文本生成模型的统一调用接口。 */
public interface LlmClient {

    /** 根据系统规则和用户问题生成回答。 */
    String generate(String systemPrompt, String userPrompt);
}