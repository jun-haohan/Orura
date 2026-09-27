package com.junhaohan.knowledgeingestion.agent.model;

import tools.jackson.databind.JsonNode;

/** 表示模型本轮只能调用一个工具或直接给出最终回答。 */
public sealed interface AgentAction permits AgentAction.CallTool, AgentAction.FinalAnswer {

    /** 携带调用 ID、模型建议的工具名和待校验参数。 */
    record CallTool(String toolCallId, String name, JsonNode arguments) implements AgentAction {
    }

    /** 携带模型给出的最终回答。 */
    record FinalAnswer(String answer) implements AgentAction {
    }
}