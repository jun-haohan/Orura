package com.junhaohan.knowledgeingestion.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.junhaohan.knowledgeingestion.agent.model.AgentResponse;
import com.junhaohan.knowledgeingestion.agent.model.AgentStatus;
import com.junhaohan.knowledgeingestion.agent.model.AgentStep;
import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import com.junhaohan.knowledgeingestion.agent.service.AgentService;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.model.RagStatus;
import com.junhaohan.knowledgeingestion.rag.service.RagService;
import com.junhaohan.knowledgeingestion.workflow.binding.InputBindingResolver;
import com.junhaohan.knowledgeingestion.workflow.config.WorkflowProperties;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Binding;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Edge;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.InputField;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Node;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.NodeType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.ValueType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.Status;
import com.junhaohan.knowledgeingestion.workflow.runtime.WorkflowExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 验证证据分支、Agent 自取引用和确定性工具的执行边界。 */
class WorkflowBranchAndToolTest {

    private final RagService rag = mock(RagService.class);
    private final AgentService agent = mock(AgentService.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final WorkflowExecutor executor = new WorkflowExecutor(rag, agent, tools,
            new ObjectMapper(), new InputBindingResolver(),
            new WorkflowProperties(20, 20, 180, 300, 1000));

    /** 有证据和无证据分别选择不同 END，未选分支不产生节点记录。 */
    @Test
    void selectsBothEvidenceBranches() {
        RagCitation source = new RagCitation(1, "doc2", "chunk7", "原文证据");
        when(rag.ask(any())).thenReturn(
                new RagResponse(RagStatus.ANSWERED, "依据原文[1]", List.of(source)),
                new RagResponse(RagStatus.INSUFFICIENT_EVIDENCE, "没有依据", List.of()));

        WorkflowRun answered = executor.execute(branchDefinition(), running(Map.of("question", "问题")));
        WorkflowRun missing = executor.execute(branchDefinition(), running(Map.of("question", "问题")));

        assertEquals(Status.SUCCEEDED, answered.status());
        assertEquals(List.of(source), answered.citations());
        assertEquals("answered", answered.nodeRuns().get(3).nodeId());
        assertEquals(Status.SUCCEEDED, missing.status());
        assertEquals("没有足够依据。", missing.answer());
        assertTrue(missing.citations().isEmpty());
        assertEquals("missing", missing.nodeRuns().get(3).nodeId());
    }

    /** Agent 获取上游知识后须在固定范围内自行调用 rag_ask 并产生引用。 */
    @Test
    void passesKnowledgeToAgentAndUsesItsOwnCitations() {
        RagCitation source = new RagCitation(1, "doc2", "chunk7", "指标为 9");
        when(rag.ask(any())).thenReturn(new RagResponse(RagStatus.ANSWERED, "指标为 9[1]", List.of(source)));
        when(agent.run(any())).thenAnswer(invocation -> {
            com.junhaohan.knowledgeingestion.agent.model.AgentRequest request = invocation.getArgument(0);
            assertEquals(List.of("doc2"), request.documentIds());
            assertEquals(List.of("rag_ask", "calculator"), request.allowedTools());
            assertEquals(2, request.maxSteps());
            assertTrue(request.task().contains("指标为 9[1]"));
            assertTrue(request.task().contains("先调用 rag_ask"));
            return new AgentResponse(AgentStatus.COMPLETED, "计算结果为 18[1]", List.of(source),
                    List.of(new AgentStep(1, "rag_ask", AgentStep.StepStatus.SUCCEEDED, "查得指标")));
        });

        WorkflowRun result = executor.execute(knowledgeAgentDefinition(), running(Map.of(
                "question", "指标是多少？", "documentIds", List.of("doc1", "doc2"))));

        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals("计算结果为 18[1]", result.answer());
        assertEquals(List.of(source), result.citations());
        assertEquals("rag_ask", ((List<?>) result.nodeRuns().get(3).output().get("steps"))
                .stream().map(item -> ((Map<?, ?>) item).get("tool")).findFirst().orElseThrow());
    }

    /** 知识任务虽然完成，但 Agent 未提供自身引用时不能发布答案。 */
    @Test
    void rejectsKnowledgeAnswerWithoutAgentCitations() {
        when(rag.ask(any())).thenReturn(new RagResponse(RagStatus.ANSWERED, "指标为 9[1]",
                List.of(new RagCitation(1, "doc2", "chunk7", "指标为 9"))));
        when(agent.run(any())).thenReturn(new AgentResponse(AgentStatus.COMPLETED,
                "结果为 18", List.of(), List.of()));

        WorkflowRun result = executor.execute(knowledgeAgentDefinition(), running(Map.of(
                "question", "指标是多少？", "documentIds", List.of("doc2"))));

        assertEquals(Status.FAILED, result.status());
        assertEquals("agent", result.nodeRuns().get(3).nodeId());
        assertEquals("NODE_ERROR", result.errorCode());
    }

    /** Agent 模型失败应终止当前路径，不能改走证据不足分支。 */
    @Test
    void stopsOnAgentModelFailure() {
        when(rag.ask(any())).thenReturn(new RagResponse(RagStatus.ANSWERED, "指标为 9[1]",
                List.of(new RagCitation(1, "doc2", "chunk7", "指标为 9"))));
        when(agent.run(any())).thenReturn(new AgentResponse(AgentStatus.MODEL_FAILED,
                "模型失败", List.of(), List.of()));

        WorkflowRun result = executor.execute(knowledgeAgentDefinition(), running(Map.of(
                "question", "指标是多少？", "documentIds", List.of("doc2"))));

        assertEquals(Status.FAILED, result.status());
        assertEquals("agent", result.nodeRuns().get(3).nodeId());
        assertEquals(4, result.nodeRuns().size());
    }

    /** Agent 自身达到时间限制也应标记节点和流程超时。 */
    @Test
    void mapsAgentTimeoutToWorkflowTimeout() {
        when(rag.ask(any())).thenReturn(new RagResponse(RagStatus.ANSWERED, "指标为 9[1]",
                List.of(new RagCitation(1, "doc2", "chunk7", "指标为 9"))));
        when(agent.run(any())).thenReturn(new AgentResponse(AgentStatus.TIMEOUT,
                "Agent 总执行时间超限", List.of(), List.of()));

        WorkflowRun result = executor.execute(knowledgeAgentDefinition(), running(Map.of(
                "question", "指标是多少？", "documentIds", List.of("doc2"))));

        assertEquals(Status.TIMED_OUT, result.status());
        assertEquals("NODE_TIMEOUT", result.errorCode());
        assertEquals(WorkflowRun.NodeStatus.TIMED_OUT, result.nodeRuns().get(3).status());
    }

    /** 独立 TOOL 使用结构化数字参数，通过注册表完成校验和计算。 */
    @Test
    void executesRegisteredDeterministicTool() {
        when(tools.execute(eq("absolute_difference"), any(JsonNode.class), any(AgentContext.class)))
                .thenAnswer(invocation -> {
                    JsonNode arguments = invocation.getArgument(1);
                    AgentContext context = invocation.getArgument(2);
                    assertEquals("9", arguments.get("a").toString());
                    assertEquals("4", arguments.get("b").toString());
                    assertEquals(Set.of("absolute_difference"), context.allowedTools());
                    return new ToolResult(ToolResult.ToolStatus.SUCCEEDED, "绝对差 = 5", List.of());
                });

        WorkflowRun result = executor.execute(toolDefinition(), running(Map.of("a", 9, "b", 4)));

        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals("绝对差 = 5", result.answer());
        assertTrue(result.citations().isEmpty());
        assertEquals("calc", result.nodeRuns().get(1).nodeId());
    }

    /** 构造两个终点的证据判断流程。 */
    private WorkflowDefinition branchDefinition() {
        Node search = new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, null);
        Node check = new Node("check", NodeType.CONDITION, null, null,
                Map.of("left", "nodes.search.status", "op", "EQUALS", "right", "ANSWERED"));
        Node answered = new Node("answered", NodeType.END, null,
                Map.of("answer", new Binding("nodes.search.answer", null),
                        "citations", new Binding("nodes.search.citations", null)), null);
        Node missing = new Node("missing", NodeType.END, null,
                Map.of("answer", new Binding(null, "没有足够依据。"),
                        "citations", new Binding(null, List.of())), null);
        return definition(List.of(start(), search, check, answered, missing),
                List.of(edge("start", "search"), edge("search", "check"),
                        new Edge("check", "answered", "true"), new Edge("check", "missing", "false")),
                Map.of("question", new InputField(ValueType.STRING, true)));
    }

    /** 构造有证据时将 RAG 答案传给 Agent 的流程。 */
    private WorkflowDefinition knowledgeAgentDefinition() {
        Node search = new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, null);
        Node check = new Node("check", NodeType.CONDITION, null, null,
                Map.of("left", "nodes.search.status", "op", "EQUALS", "right", "ANSWERED"));
        Node agentNode = new Node("agent", NodeType.AGENT,
                Map.of("task", new Binding(null, "请计算指标的两倍"),
                        "context", new Binding("nodes.search.answer", null)), null,
                Map.of("allowedTools", List.of("rag_ask", "calculator"), "maxSteps", 2,
                        "documentIds", List.of("doc2")));
        Node finish = new Node("finish", NodeType.END, null,
                Map.of("answer", new Binding("nodes.agent.answer", null),
                        "citations", new Binding("nodes.agent.citations", null)), null);
        Node missing = new Node("missing", NodeType.END, null,
                Map.of("answer", new Binding(null, "没有足够依据。"),
                        "citations", new Binding(null, List.of())), null);
        return definition(List.of(start(), search, check, agentNode, finish, missing),
                List.of(edge("start", "search"), edge("search", "check"),
                        new Edge("check", "agent", "true"), edge("agent", "finish"),
                        new Edge("check", "missing", "false")),
                Map.of("question", new InputField(ValueType.STRING, true)));
    }

    /** 构造可以直接计算两个运行输入绝对差的流程。 */
    private WorkflowDefinition toolDefinition() {
        Node calc = new Node("calc", NodeType.TOOL,
                Map.of("a", new Binding("input.a", null), "b", new Binding("input.b", null)),
                null, Map.of("tool", "absolute_difference"));
        Node finish = new Node("finish", NodeType.END, null,
                Map.of("answer", new Binding("nodes.calc.content", null)), null);
        return definition(List.of(start(), calc, finish),
                List.of(edge("start", "calc"), edge("calc", "finish")),
                Map.of("a", new InputField(ValueType.NUMBER, true),
                        "b", new InputField(ValueType.NUMBER, true)));
    }

    /** 构造一个版本固定的流程定义。 */
    private WorkflowDefinition definition(List<Node> nodes, List<Edge> edges,
                                          Map<String, InputField> inputs) {
        return new WorkflowDefinition("wf:1", "wf", 1, "test", inputs, nodes, edges, Instant.EPOCH);
    }

    /** 构造唯一的 START 节点。 */
    private Node start() {
        return new Node("start", NodeType.START, null, null, null);
    }

    /** 构造普通无分支边。 */
    private Edge edge(String from, String to) {
        return new Edge(from, to, null);
    }

    /** 构造一次尚未执行的运行记录。 */
    private WorkflowRun running(Map<String, Object> input) {
        return new WorkflowRun("run1", "wf", 1, Status.RUNNING, input, List.of(), null,
                List.of(), Instant.now(), null, null, null);
    }
}
