package com.junhaohan.knowledgeingestion.agent.service;

import com.junhaohan.knowledgeingestion.agent.config.AgentProperties;
import com.junhaohan.knowledgeingestion.agent.model.AgentRequest;
import com.junhaohan.knowledgeingestion.agent.model.AgentResponse;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentExecutor;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 校验外部请求并建立服务端固定的 Agent 运行范围。 */
@Service
public class AgentService {

    private final ToolRegistry registry;
    private final AgentProperties properties;
    private final AgentExecutor executor;

    /** 注入工具注册表、服务端预算和执行器。 */
    public AgentService(ToolRegistry registry, AgentProperties properties, AgentExecutor executor) {
        this.registry = registry;
        this.properties = properties;
        this.executor = executor;
    }

    /** 校验任务、工具白名单及工具调用预算后启动执行。 */
    public AgentResponse run(AgentRequest request) {
        if (request == null || request.task() == null || request.task().isBlank()) {
            throw new IllegalArgumentException("Agent 任务不能为空");
        }
        String task = request.task().strip();
        if (task.length() > properties.maxTaskChars()) {
            throw new IllegalArgumentException("Agent 任务超过最大字符数");
        }
        int steps = request.maxSteps() == null ? 1 : request.maxSteps();
        if (steps < 1 || steps > properties.maxSteps()) {
            throw new IllegalArgumentException("maxSteps 超出服务端允许范围");
        }
        Set<String> allowed = registry.allowedTools(request.allowedTools());
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("没有可用的 Agent 工具");
        }
        AgentContext context = new AgentContext(task, request.documentIds(), allowed, steps);
        return executor.run(context);
    }
}
