package com.junhaohan.knowledgeingestion.agent.controller;

import com.junhaohan.knowledgeingestion.agent.model.AgentRequest;
import com.junhaohan.knowledgeingestion.agent.model.AgentResponse;
import com.junhaohan.knowledgeingestion.agent.service.AgentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对外提供有限步 Agent 任务执行接口。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    /** 注入 Agent 业务入口。 */
    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 接收任务并返回工具轨迹、答案与真实引用。 */
    @PostMapping("/run")
    public AgentResponse run(@RequestBody AgentRequest request) {
        return agentService.run(request);
    }
}
