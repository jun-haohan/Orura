package com.junhaohan.knowledgeingestion.agent.runtime;

import java.util.List;
import java.util.Set;

/** 固定本次任务的文档范围、可用工具和最大工具调用次数。 */
public record AgentContext(
        String task,
        List<String> documentIds,
        Set<String> allowedTools,
        int maxSteps
) {
    /** 规范化输入并冻结本次运行的授权范围。 */
    public AgentContext {
        if (task == null || task.isBlank() || allowedTools == null || allowedTools.isEmpty()
                || maxSteps < 1) {
            throw new IllegalArgumentException("Agent 任务、可用工具和步数不能为空");
        }
        task = task.strip();
        if (documentIds != null) {
            if (documentIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("documentIds 不能包含空文档 ID");
            }
            documentIds = documentIds.isEmpty() ? null
                    : documentIds.stream().map(String::strip).distinct().toList();
        }
        if (allowedTools.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("allowedTools 不能包含空工具名");
        }
        allowedTools = Set.copyOf(allowedTools);
    }
}