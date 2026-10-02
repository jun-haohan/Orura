package com.junhaohan.knowledgeingestion.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.agent.service.AgentService;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.rag.model.RagRequest;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.model.RagStatus;
import com.junhaohan.knowledgeingestion.rag.service.RagService;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalMode;
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
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.NodeStatus;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.Status;
import com.junhaohan.knowledgeingestion.workflow.repository.WorkflowRunRepository;
import com.junhaohan.knowledgeingestion.workflow.runtime.WorkflowExecutor;
import com.junhaohan.knowledgeingestion.workflow.service.WorkflowDefinitionService;
import com.junhaohan.knowledgeingestion.workflow.service.WorkflowRunService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证 RAG 实际调用、引用传递、文档范围及运行状态持久化。 */
class WorkflowExecutorTest {

    private final RagService ragService = mock(RagService.class);
    private final WorkflowExecutor executor = new WorkflowExecutor(ragService,
            mock(AgentService.class), mock(ToolRegistry.class), new ObjectMapper(),
            new InputBindingResolver(), new WorkflowProperties(20, 20, 180, 300, 1000));

    /** 只查询输入范围与节点范围的交集，并返回已有 RAG 生成的引用。 */
    @Test
    void returnsAnswerAndOriginalCitationWithinDocumentScope() {
        RagRequest expected = new RagRequest("如何部署？", List.of("doc2"), RetrievalMode.HYBRID, 5, true);
        RagCitation citation = new RagCitation(1, "doc2", "chunk7", "部署说明");
        when(ragService.ask(expected)).thenReturn(new RagResponse(RagStatus.ANSWERED,
                "根据部署说明[1]。", List.of(citation)));
        Map<String, Object> input = Map.of("question", "如何部署？",
                "documentIds", List.of("doc1", "doc2"));

        WorkflowRun result = executor.execute(definition(Map.of("mode", "HYBRID", "topK", 5,
                "rerank", true, "documentIds", List.of("doc2", "outside"))), running(input));

        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals("根据部署说明[1]。", result.answer());
        assertEquals(List.of(citation), result.citations());
        assertEquals(List.of("start", "search", "finish"), result.nodeRuns().stream()
                .map(WorkflowRun.NodeRun::nodeId).toList());
        assertEquals("ANSWERED", result.nodeRuns().get(1).output().get("status"));
        verify(ragService).ask(expected);
    }

    /** 节点要求收窄范围时，缺少输入范围不能退化成全库检索。 */
    @Test
    void failsBeforeRagWhenScopeIsMissing() {
        WorkflowRun result = executor.execute(definition(Map.of("documentIds", List.of("doc2"))),
                running(Map.of("question", "如何部署？")));

        assertEquals(Status.FAILED, result.status());
        assertEquals("INPUT_ERROR", result.errorCode());
        assertEquals("search", result.nodeRuns().get(1).nodeId());
        assertEquals(NodeStatus.FAILED, result.nodeRuns().get(1).status());
        verify(ragService, never()).ask(any(RagRequest.class));
    }

    /** 显式的空文档范围由 START 拒绝。 */
    @Test
    void rejectsEmptyDocumentScopeAtStart() {
        WorkflowRun result = executor.execute(definition(Map.of()), running(
                Map.of("question", "如何部署？", "documentIds", List.of())));

        assertEquals(Status.FAILED, result.status());
        assertEquals("INPUT_ERROR", result.errorCode());
        assertEquals("start", result.nodeRuns().get(0).nodeId());
        verify(ragService, never()).ask(any(RagRequest.class));
    }

    /** 证据不足是 RAG 业务输出，仍可由 END 明确返回。 */
    @Test
    void returnsInsufficientEvidenceWithoutInventingCitations() {
        when(ragService.ask(any(RagRequest.class))).thenReturn(new RagResponse(
                RagStatus.INSUFFICIENT_EVIDENCE, "没有足够依据。", List.of()));

        WorkflowRun result = executor.execute(definition(Map.of()),
                running(Map.of("question", "未知问题")));

        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals("INSUFFICIENT_EVIDENCE", result.nodeRuns().get(1).output().get("status"));
        assertEquals("没有足够依据。", result.answer());
        assertTrue(result.citations().isEmpty());
    }

    /** 节点抛出异常时保存失败节点，不能返回伪造的成功答案。 */
    @Test
    void stopsOnRagFailure() {
        when(ragService.ask(any(RagRequest.class))).thenThrow(new IllegalStateException("service down"));

        WorkflowRun result = executor.execute(definition(Map.of()),
                running(Map.of("question", "如何部署？")));

        assertEquals(Status.FAILED, result.status());
        assertEquals("NODE_ERROR", result.errorCode());
        assertEquals(2, result.nodeRuns().size());
        assertNull(result.answer());
    }

    /** 验证运行服务先插入 RUNNING，再保存执行后的终态。 */
    @Test
    void persistsRunningBeforeFinalResult() {
        WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowDefinition definition = definition(Map.of());
        when(definitions.get("wf", 1)).thenReturn(definition);
        when(ragService.ask(any(RagRequest.class))).thenReturn(new RagResponse(
                RagStatus.INSUFFICIENT_EVIDENCE, "没有足够依据。", List.of()));
        when(repository.insert(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun item = invocation.getArgument(0);
            assertEquals(Status.RUNNING, item.status());
            return item;
        });
        List<WorkflowRun> snapshots = new ArrayList<>();
        when(repository.save(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun item = invocation.getArgument(0);
            snapshots.add(item);
            return item;
        });

        WorkflowRun result = new WorkflowRunService(definitions, repository, executor)
                .start("wf", new WorkflowRun.StartRequest(1, Map.of("question", "问题")));

        assertEquals(1, result.version());
        assertEquals(3, result.nodeRuns().size());
        assertEquals(Status.RUNNING, snapshots.get(0).status());
        assertEquals(NodeStatus.RUNNING, snapshots.get(0).nodeRuns().get(0).status());
        assertEquals(Status.SUCCEEDED, snapshots.get(snapshots.size() - 1).status());
        assertTrue(snapshots.stream().anyMatch(item -> item.nodeRuns().size() == 2
                && item.nodeRuns().get(1).status() == NodeStatus.RUNNING));
        verify(repository).insert(any(WorkflowRun.class));
    }

    /** 节点调用超过独立预算时中断执行，记录节点及流程超时。 */
    @Test
    void timesOutBlockingNode() {
        RagService blocking = mock(RagService.class);
        when(blocking.ask(any(RagRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            throw new IllegalStateException("不应完成阻塞调用");
        });
        WorkflowExecutor limited = new WorkflowExecutor(blocking, mock(AgentService.class),
                mock(ToolRegistry.class), new ObjectMapper(), new InputBindingResolver(),
                new WorkflowProperties(20, 20, 1, 3, 1000));
        try {
            WorkflowRun result = limited.execute(definition(Map.of()), running(Map.of("question", "问题")));
            assertEquals(Status.TIMED_OUT, result.status());
            assertEquals("NODE_TIMEOUT", result.errorCode());
            assertEquals("search", result.nodeRuns().get(1).nodeId());
            assertEquals(NodeStatus.TIMED_OUT, result.nodeRuns().get(1).status());
        } finally {
            limited.shutdown();
        }
    }

    /** 总预算先耗尽时记录总超时，不再执行后续节点。 */
    @Test
    void timesOutWholeWorkflow() {
        RagService blocking = mock(RagService.class);
        when(blocking.ask(any(RagRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            throw new IllegalStateException("不应完成阻塞调用");
        });
        WorkflowExecutor limited = new WorkflowExecutor(blocking, mock(AgentService.class),
                mock(ToolRegistry.class), new ObjectMapper(), new InputBindingResolver(),
                new WorkflowProperties(20, 20, 2, 2, 1000));
        try {
            WorkflowRun result = limited.execute(definition(Map.of()), running(Map.of("question", "问题")));
            assertEquals(Status.TIMED_OUT, result.status());
            assertEquals("WORKFLOW_TIMEOUT", result.errorCode());
            assertEquals("search", result.nodeRuns().get(1).nodeId());
            assertEquals(2, result.nodeRuns().size());
        } finally {
            limited.shutdown();
        }
    }

    /** 启动恢复只标记遗留运行和当时执行的节点，不重放旧任务。 */
    @Test
    void recoversOrphanRunningRecord() {
        WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowRun orphan = new WorkflowRun("old", "wf", 1, Status.RUNNING,
                Map.of("question", "问题"), List.of(
                new WorkflowRun.NodeRun("start", NodeStatus.SUCCEEDED, Instant.EPOCH,
                        Instant.EPOCH, Map.of("status", "READY"), null, null),
                new WorkflowRun.NodeRun("search", NodeStatus.RUNNING, Instant.EPOCH,
                        null, Map.of(), null, null)), null, List.of(), Instant.EPOCH,
                null, null, null);
        when(repository.findByStatus(Status.RUNNING)).thenReturn(List.of(orphan));
        when(repository.save(any(WorkflowRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowRunService service = new WorkflowRunService(definitions, repository, executor);
        service.recoverInterruptedRuns();

        org.mockito.ArgumentCaptor<WorkflowRun> saved = org.mockito.ArgumentCaptor.forClass(WorkflowRun.class);
        verify(repository).save(saved.capture());
        assertEquals(Status.FAILED, saved.getValue().status());
        assertEquals("PROCESS_INTERRUPTED", saved.getValue().errorCode());
        assertEquals(NodeStatus.SUCCEEDED, saved.getValue().nodeRuns().get(0).status());
        assertEquals(NodeStatus.FAILED, saved.getValue().nodeRuns().get(1).status());
        assertEquals("search", saved.getValue().nodeRuns().get(1).nodeId());
    }

    /** 执行器意外中止时，运行服务仍保存最后可见的失败位置。 */
    @Test
    void persistsUnexpectedExecutorFailure() {
        WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowExecutor broken = mock(WorkflowExecutor.class);
        when(definitions.get("wf", 1)).thenReturn(definition(Map.of()));
        when(broken.execute(any(WorkflowDefinition.class), any(WorkflowRun.class), any()))
                .thenThrow(new IllegalStateException("unexpected"));
        when(repository.save(any(WorkflowRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowRun result = new WorkflowRunService(definitions, repository, broken)
                .start("wf", new WorkflowRun.StartRequest(1, Map.of("question", "问题")));

        assertEquals(Status.FAILED, result.status());
        assertEquals("EXECUTION_ERROR", result.errorCode());
        assertTrue(result.nodeRuns().isEmpty());
        verify(repository).save(any(WorkflowRun.class));
    }

    /** 构造仅包含 START、RAG、END 的已保存定义。 */
    private WorkflowDefinition definition(Map<String, Object> config) {
        Node start = new Node("start", NodeType.START, null, null, null);
        Node search = new Node("search", NodeType.RAG,
                Map.of("question", new Binding("input.question", null)), null, config);
        Node end = new Node("finish", NodeType.END, null,
                Map.of("answer", new Binding("nodes.search.answer", null),
                        "citations", new Binding("nodes.search.citations", null)), null);
        return new WorkflowDefinition("wf:1", "wf", 1, "RAG 问答",
                Map.of("question", new InputField(ValueType.STRING, true)),
                List.of(start, search, end),
                List.of(new Edge("start", "search", null), new Edge("search", "finish", null)),
                Instant.EPOCH);
    }

    /** 构造一个尚未执行的、绑定版本 1 的运行。 */
    private WorkflowRun running(Map<String, Object> input) {
        return new WorkflowRun("run1", "wf", 1, Status.RUNNING, input, List.of(), null,
                List.of(), Instant.now(), null, null, null);
    }
}