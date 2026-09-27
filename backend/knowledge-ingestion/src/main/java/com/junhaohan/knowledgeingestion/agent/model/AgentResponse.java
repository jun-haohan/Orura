package com.junhaohan.knowledgeingestion.agent.model;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import java.util.List;

/** 返回 Agent 运行状态、最终答案、引用和工具轨迹。 */
public record AgentResponse(
        AgentStatus status,
        String answer,
        List<RagCitation> citations,
        List<AgentStep> steps
) {
}