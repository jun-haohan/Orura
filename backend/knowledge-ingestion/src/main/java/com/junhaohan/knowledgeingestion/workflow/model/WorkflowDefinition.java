package com.junhaohan.knowledgeingestion.workflow.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** 保存一个不可覆盖的流程定义版本。 */
@Document("workflow_definitions")
@CompoundIndex(name = "workflow_version_unique", def = "{'workflowId': 1, 'version': 1}", unique = true)
public record WorkflowDefinition(
        @Id String id,
        String workflowId,
        int version,
        String name,
        Map<String, InputField> inputFields,
        List<Node> nodes,
        List<Edge> edges,
        Instant createdAt
) {
    /** 接收待创建的新流程或新版本。 */
    public record SaveRequest(
            String name,
            Map<String, InputField> inputFields,
            List<Node> nodes,
            List<Edge> edges
    ) {
    }

    /** 声明运行输入字段的类型及是否必填。 */
    public record InputField(ValueType type, boolean required) {
    }

    /** 定义节点及其允许的输入、输出绑定。 */
    public record Node(
            String id,
            NodeType type,
            Map<String, Binding> inputBindings,
            Map<String, Binding> outputBindings,
            Map<String, Object> config
    ) {
    }

    /** 定义两个节点之间的连线及条件分支。 */
    public record Edge(String from, String to, String branch) {
    }

    /** 只允许取常量或指定字段路径，禁止执行表达式。 */
    public record Binding(String path, Object value) {
    }

    /** 列举首版执行器识别的节点类型。 */
    public enum NodeType {
        START, RAG, AGENT, TOOL, CONDITION, END
    }

    /** 列举可检查的绑定值类型。 */
    public enum ValueType {
        STRING, NUMBER, BOOLEAN, STRING_LIST, CITATIONS
    }
}