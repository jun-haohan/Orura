package com.junhaohan.knowledgeingestion.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 保存服务端统一控制的流程大小、时间和输出预算。 */
@Component
public record WorkflowProperties(
        @Value("${workflow.max-nodes:20}") int maxNodes,
        @Value("${workflow.max-node-executions:20}") int maxNodeExecutions,
        @Value("${workflow.node-timeout-seconds:180}") int nodeTimeoutSeconds,
        @Value("${workflow.total-timeout-seconds:300}") int totalTimeoutSeconds,
        @Value("${workflow.max-output-chars:1000}") int maxOutputChars
) {
    /** 拒绝无效配置，避免在运行期间才暴露预算错误。 */
    public WorkflowProperties {
        if (maxNodes < 2 || maxNodeExecutions < 2 || nodeTimeoutSeconds < 1
                || totalTimeoutSeconds < nodeTimeoutSeconds || maxOutputChars < 1) {
            throw new IllegalArgumentException("Workflow 预算配置不合法");
        }
    }
}