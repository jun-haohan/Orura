package com.junhaohan.knowledgeingestion.agent.model;

/** 记录一次工具调用的可公开轨迹。 */
public record AgentStep(int index, String tool, StepStatus status, String summary) {

    /** 标识一次工具调用的执行结果。 */
    public enum StepStatus {
        SUCCEEDED,
        FAILED,
        REJECTED,
        TIMEOUT
    }
}