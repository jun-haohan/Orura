package com.junhaohan.knowledgeingestion.workflow.model;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 保存一次绑定了定义版本的流程运行及最终结果。 */
@Document("workflow_runs")
public record WorkflowRun(
        @Id String id,
        String workflowId,
        int version,
        Status status,
        Map<String, Object> input,
        List<NodeRun> nodeRuns,
        String answer,
        List<RagCitation> citations,
        Instant startedAt,
        Instant endedAt,
        String errorCode,
        String errorMessage
) {
    /** 接收指定版本或当前版本的一次运行输入。 */
    public record StartRequest(Integer version, Map<String, Object> input) {
    }

    /** 记录一个实际执行节点的时间、状态和有限输出。 */
    public record NodeRun(
            String nodeId,
            NodeStatus status,
            Instant startedAt,
            Instant endedAt,
            Map<String, Object> output,
            String errorCode,
            String errorMessage
    ) {
    }

    /** 区分流程执行中、成功、失败及超时。 */
    public enum Status {
        RUNNING, SUCCEEDED, FAILED, TIMED_OUT
    }

    /** 区分实际执行节点的不同结束状态。 */
    public enum NodeStatus {
        RUNNING, SUCCEEDED, FAILED, TIMED_OUT
    }
}