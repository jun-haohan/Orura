package com.junhaohan.knowledgeingestion.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junhaohan.knowledgeingestion.agent.config.AgentProperties;
import com.junhaohan.knowledgeingestion.agent.tool.AbsoluteDifferenceTool;
import com.junhaohan.knowledgeingestion.agent.tool.CalculatorTool;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.workflow.binding.InputBindingResolver;
import com.junhaohan.knowledgeingestion.workflow.config.WorkflowProperties;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Binding;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Edge;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Node;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.NodeType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.SaveRequest;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.ValueType;
import com.junhaohan.knowledgeingestion.workflow.validation.WorkflowGraphValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 核对合法分支、循环、跨分支引用及工具边界。 */
class WorkflowGraphValidatorTest {

    private final InputBindingResolver bindings = new InputBindingResolver();
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator(
            bindings, new WorkflowProperties(20, 20, 180, 300, 1000),
            new ToolRegistry(List.of(new CalculatorTool(), new AbsoluteDifferenceTool())),
            new AgentProperties(4, 30, 60, 180, 1000, 160, 2000), 10);

    /** 允许有两个终点的证据状态分支。 */
    @Test
    void acceptsTwoEndBranches() {
        assertDoesNotThrow(() -> validator.validate(answerOrNoEvidence()));
    }

    /** 在定义期拒绝引用未执行条件分支的输出。 */
    @Test
    void rejectsCrossBranchBinding() {
        SaveRequest valid = answerOrNoEvidence();
        List<Node> nodes = new java.util.ArrayList<>(valid.nodes());
        nodes.set(4, new Node("missing", NodeType.END, null,
                Map.of("answer", new Binding("nodes.answered.answer", null)), null));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new SaveRequest(valid.name(), null, nodes, valid.edges())));
        assertTrue(error.getMessage().contains("必经上游"));
    }

    /** 拒绝从起点不可达的自环，而非保存后才发生死循环。 */
    @Test
    void rejectsCycle() {
        Node loop = new Node("loop", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, null);
        SaveRequest invalid = new SaveRequest("cycle", null,
                List.of(start(), end("finish", new Binding(null, "完成")), loop),
                List.of(new Edge("start", "finish", null), new Edge("loop", "loop", null)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(invalid));
        assertTrue(error.getMessage().contains("循环"));
    }

    /** TOOL 节点只接受已注册的确定性工具。 */
    @Test
    void rejectsKnowledgeToolAsDeterministicTool() {
        Node tool = new Node("calc", NodeType.TOOL,
                Map.of("question", new Binding("input.question", null)), null,
                Map.of("tool", "rag_ask"));
        SaveRequest invalid = new SaveRequest("tool", null,
                List.of(start(), tool, end("finish", new Binding("nodes.calc.content", null))),
                List.of(new Edge("start", "calc", null), new Edge("calc", "finish", null)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(invalid));
        assertTrue(error.getMessage().contains("确定性工具"));
    }

    /** 缩小文档范围时不能使用会在 RAG 中变成全库检索的空列表。 */
    @Test
    void rejectsEmptyDocumentScope() {
        SaveRequest valid = answerOrNoEvidence();
        List<Node> nodes = new java.util.ArrayList<>(valid.nodes());
        nodes.set(1, new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null,
                Map.of("documentIds", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new SaveRequest(valid.name(), null, nodes, valid.edges())));
    }

    /** 缺失的动态值在运行时以 INPUT_ERROR 报出。 */
    @Test
    void rejectsMissingBoundValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> bindings.resolve(new Binding("input.question", null), Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("INPUT_ERROR"));
    }

    /** 声明为字符串的运行输入不接受实际传来的数字或空白文本。 */
    @Test
    void rejectsWrongRuntimeType() {
        Binding question = new Binding("input.question", null);
        IllegalArgumentException number = assertThrows(IllegalArgumentException.class,
                () -> bindings.resolve(question, ValueType.STRING, Map.of("question", 42), Map.of()));
        assertTrue(number.getMessage().contains("INPUT_ERROR"));
        assertThrows(IllegalArgumentException.class,
                () -> bindings.resolve(question, ValueType.STRING, Map.of("question", " "), Map.of()));
    }

    /** 答案来自知识节点时必须同时返回同一节点的引用。 */
    @Test
    void rejectsAnswerWithoutMatchingCitations() {
        SaveRequest valid = answerOrNoEvidence();
        List<Node> nodes = new java.util.ArrayList<>(valid.nodes());
        nodes.set(3, end("answered", new Binding("nodes.search.answer", null)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new SaveRequest(valid.name(), null, nodes, valid.edges())));
        assertTrue(error.getMessage().contains("同一节点的引用"));
    }

    /** 节点任务必须有合法的、非空白的字符串绑定。 */
    @Test
    void rejectsEmptyTask() {
        Node agent = new Node("agent", NodeType.AGENT,
                Map.of("task", new Binding(null, " ")), null,
                Map.of("allowedTools", List.of("calculator")));
        SaveRequest invalid = new SaveRequest("task", null,
                List.of(start(), agent, end("finish", new Binding("nodes.agent.answer", null))),
                List.of(new Edge("start", "agent", null), new Edge("agent", "finish", null)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(invalid));
        assertTrue(error.getMessage().contains("必填字段"));
    }

    /** 不允许在 Agent 白名单中写入未注册的工具。 */
    @Test
    void rejectsUnknownAgentTool() {
        Node agent = new Node("agent", NodeType.AGENT,
                Map.of("task", new Binding("input.task", null)), null,
                Map.of("allowedTools", List.of("missing_tool")));
        SaveRequest invalid = new SaveRequest("tool", null,
                List.of(start(), agent, end("finish", new Binding(null, "完成"))),
                List.of(new Edge("start", "agent", null), new Edge("agent", "finish", null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(invalid));
    }

    /** 构造有证据与无证据分别结束的最小流程。 */
    private SaveRequest answerOrNoEvidence() {
        Node search = new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, null);
        Node condition = new Node("check", NodeType.CONDITION, null, null,
                Map.of("left", "nodes.search.status", "op", "EQUALS", "right", "ANSWERED"));
        Node answered = new Node("answered", NodeType.END, null,
                Map.of("answer", new Binding("nodes.search.answer", null),
                        "citations", new Binding("nodes.search.citations", null)), null);
        Node missing = new Node("missing", NodeType.END, null,
                Map.of("answer", new Binding(null, "当前文档没有足够依据。"),
                        "citations", new Binding(null, List.of())), null);
        return new SaveRequest("证据判断", null,
                List.of(start(), search, condition, answered, missing),
                List.of(new Edge("start", "search", null), new Edge("search", "check", null),
                        new Edge("check", "answered", "true"),
                        new Edge("check", "missing", "false")));
    }

    /** 构造唯一的起点。 */
    private Node start() {
        return new Node("start", NodeType.START, null, null, null);
    }

    /** 构造返回指定答案的终点。 */
    private Node end(String id, Binding answer) {
        return new Node(id, NodeType.END, null, Map.of("answer", answer), null);
    }

    /** Agent 直接把 RAG 答案当任务时仍必须允许自行取得引用。 */
    @Test
    void requiresRagAskForKnowledgeTask() {
        Node search = new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, null);
        Node agent = new Node("agent", NodeType.AGENT,
                Map.of("task", new Binding("nodes.search.answer", null)), null,
                Map.of("allowedTools", List.of("calculator")));
        Node finish = new Node("finish", NodeType.END, null,
                Map.of("answer", new Binding("nodes.agent.answer", null),
                        "citations", new Binding("nodes.agent.citations", null)), null);
        SaveRequest invalid = new SaveRequest("知识计算", null,
                List.of(start(), search, agent, finish),
                List.of(new Edge("start", "search", null), new Edge("search", "agent", null),
                        new Edge("agent", "finish", null)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(invalid));
        assertTrue(error.getMessage().contains("rag_ask"));
    }

    /** 普通节点的空白分支标识不能在运行时造成无匹配边。 */
    @Test
    void rejectsBlankBranchOnOrdinaryNode() {
        SaveRequest valid = answerOrNoEvidence();
        List<Edge> edges = new java.util.ArrayList<>(valid.edges());
        edges.set(0, new Edge("start", "search", ""));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new SaveRequest(valid.name(), null, valid.nodes(), edges)));
    }
}