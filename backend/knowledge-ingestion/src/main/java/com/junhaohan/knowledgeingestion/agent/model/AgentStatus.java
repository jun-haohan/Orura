package com.junhaohan.knowledgeingestion.agent.model;

/** 区分任务完成、证据不足和不同运行终止原因。 */
public enum AgentStatus {
    COMPLETED,
    INSUFFICIENT_EVIDENCE,
    MAX_STEPS_REACHED,
    TOOL_FAILED,
    MODEL_FAILED,
    TIMEOUT
}