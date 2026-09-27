package com.junhaohan.knowledgeingestion.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 保存服务端的 Agent 步数、超时与输出长度上限。 */
@Component
public class AgentProperties {

    private final int maxSteps;
    private final int modelTimeoutSeconds;
    private final int stepTimeoutSeconds;
    private final int totalTimeoutSeconds;
    private final int maxTaskChars;
    private final int maxStepSummaryChars;
    private final int maxToolResultChars;

    /** 读取配置并拒绝无效的预算和超时值。 */
    public AgentProperties(
            @Value("${agent.max-steps:4}") int maxSteps,
            @Value("${agent.model-timeout-seconds:30}") int modelTimeoutSeconds,
            @Value("${agent.step-timeout-seconds:60}") int stepTimeoutSeconds,
            @Value("${agent.total-timeout-seconds:180}") int totalTimeoutSeconds,
            @Value("${agent.max-task-chars:1000}") int maxTaskChars,
            @Value("${agent.max-step-summary-chars:160}") int maxStepSummaryChars,
            @Value("${agent.max-tool-result-chars:2000}") int maxToolResultChars) {
        if (maxSteps < 1 || modelTimeoutSeconds < 1 || stepTimeoutSeconds < 1
                || totalTimeoutSeconds < stepTimeoutSeconds
                || modelTimeoutSeconds > totalTimeoutSeconds
                || maxTaskChars < 1 || maxStepSummaryChars < 1 || maxToolResultChars < 1) {
            throw new IllegalArgumentException("Agent 配置必须为正数，单步和模型超时不能超过总超时");
        }
        this.maxSteps = maxSteps;
        this.modelTimeoutSeconds = modelTimeoutSeconds;
        this.stepTimeoutSeconds = stepTimeoutSeconds;
        this.totalTimeoutSeconds = totalTimeoutSeconds;
        this.maxTaskChars = maxTaskChars;
        this.maxStepSummaryChars = maxStepSummaryChars;
        this.maxToolResultChars = maxToolResultChars;
    }

    /** 返回每次运行最多执行的工具次数。 */
    public int maxSteps() {
        return maxSteps;
    }

    /** 返回单次模型请求超时秒数。 */
    public int modelTimeoutSeconds() {
        return modelTimeoutSeconds;
    }

    /** 返回单次工具执行超时秒数。 */
    public int stepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    /** 返回整个 Agent 运行超时秒数。 */
    public int totalTimeoutSeconds() {
        return totalTimeoutSeconds;
    }

    /** 返回用户任务的最大字符数。 */
    public int maxTaskChars() {
        return maxTaskChars;
    }

    /** 返回单条公开轨迹摘要的最大字符数。 */
    public int maxStepSummaryChars() {
        return maxStepSummaryChars;
    }

    /** 返回送往模型的单条工具结果最大字符数。 */
    public int maxToolResultChars() {
        return maxToolResultChars;
    }
}