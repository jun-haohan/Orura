package com.junhaohan.knowledgeingestion.agent.model;

import java.util.List;

/** 接收一次 Agent 任务和本次运行的工具、文档范围。 */
public record AgentRequest(
        String task,
        List<String> documentIds,
        List<String> allowedTools,
        Integer maxSteps
) {
}