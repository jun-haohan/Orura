package com.junhaohan.knowledgeingestion.agent.tool;

import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 只注册服务端提供的工具，并按本次任务的白名单返回工具。 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    /** 注入工具并拒绝重名或无名称的注册项。 */
    public ToolRegistry(List<AgentTool> registeredTools) {
        Map<String, AgentTool> entries = new LinkedHashMap<>();
        for (AgentTool tool : registeredTools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()
                    || entries.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Agent 工具名称为空或重复");
            }
        }
        this.tools = Map.copyOf(entries);
    }

    /** 将客户端指定的工具限制为已注册工具；未指定时使用已注册工具。 */
    public Set<String> allowedTools(List<String> requestedNames) {
        if (requestedNames == null) {
            return Set.copyOf(tools.keySet());
        }
        if (requestedNames.isEmpty()) {
            throw new IllegalArgumentException("allowedTools 不能为空列表");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String name : requestedNames) {
            if (name == null || name.isBlank() || !tools.containsKey(name)) {
                throw new IllegalArgumentException("未注册的 Agent 工具: " + name);
            }
            allowed.add(name);
        }
        return Set.copyOf(allowed);
    }

    /** 查询本次任务可向模型展示的工具，按名称固定顺序。 */
    public List<AgentTool> available(AgentContext context) {
        List<AgentTool> available = new ArrayList<>();
        for (String name : context.allowedTools()) {
            AgentTool tool = tools.get(name);
            if (tool == null) {
                throw new IllegalArgumentException("未注册的 Agent 工具: " + name);
            }
            available.add(tool);
        }
        available.sort(Comparator.comparing(AgentTool::name));
        return List.copyOf(available);
    }

    /** 仅在工具已注册且获本次请求允许时提供执行入口。 */
    public AgentTool resolve(String name, AgentContext context) {
        if (name == null || !context.allowedTools().contains(name)) {
            throw new IllegalArgumentException("不允许调用 Agent 工具: " + name);
        }
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的 Agent 工具: " + name);
        }
        return tool;
    }

    /** 检查工具授权和参数后执行，避免调用方绕过参数校验。 */
    public ToolResult execute(String name, JsonNode arguments, AgentContext context) {
        AgentTool tool = resolve(name, context);
        tool.validateArguments(arguments);
        return tool.execute(arguments, context);
    }
}
