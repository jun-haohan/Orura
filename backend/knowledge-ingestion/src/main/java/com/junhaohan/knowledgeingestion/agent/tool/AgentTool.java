package com.junhaohan.knowledgeingestion.agent.tool;

import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 定义可由 Agent 选择、但必须由服务端校验和执行的工具。 */
public interface AgentTool {

    /** 返回工具的唯一名称。 */
    String name();

    /** 返回供模型选择工具时使用的简短描述。 */
    String description();

    /** 返回模型调用时使用的 JSON 参数 Schema。 */
    Map<String, Object> parametersSchema();

    /** 在执行前校验参数的字段、类型和取值。 */
    void validateArguments(JsonNode arguments);

    /** 使用服务端固定的运行上下文执行工具。 */
    ToolResult execute(JsonNode arguments, AgentContext context);
}