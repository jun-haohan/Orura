package com.junhaohan.knowledgeingestion.agent.llm;

import com.junhaohan.knowledgeingestion.agent.model.AgentAction;
import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import java.time.Duration;
import java.util.List;

/** 将 Agent 对话转换为一次模型工具决策或最终回答。 */
public interface AgentModelClient {

    /** 根据任务和已完成的工具调用决定下一步动作。 */
    AgentAction decide(AgentContext context, List<ToolExchange> exchanges, Duration timeout);

    /** 保存模型工具调用与对应执行结果，供下一轮模型决策使用。 */
    record ToolExchange(AgentAction.CallTool call, ToolResult result) {
    }
}
