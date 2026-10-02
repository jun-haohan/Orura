package com.junhaohan.knowledgeingestion.workflow.runtime;

import com.junhaohan.knowledgeingestion.agent.model.AgentRequest;
import com.junhaohan.knowledgeingestion.agent.model.AgentResponse;
import com.junhaohan.knowledgeingestion.agent.model.AgentStatus;
import com.junhaohan.knowledgeingestion.agent.model.AgentStep;
import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import com.junhaohan.knowledgeingestion.agent.service.AgentService;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.rag.model.RagRequest;
import com.junhaohan.knowledgeingestion.rag.model.RagResponse;
import com.junhaohan.knowledgeingestion.rag.service.RagService;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalMode;
import com.junhaohan.knowledgeingestion.workflow.binding.InputBindingResolver;
import com.junhaohan.knowledgeingestion.workflow.config.WorkflowProperties;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Binding;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.InputField;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Node;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.NodeType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.ValueType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.NodeRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.NodeStatus;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.Status;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 按已验证的边串行执行节点，保留绑定值及有限的节点摘要。 */
@Component
public class WorkflowExecutor {

    private final RagService ragService;
    private final AgentService agentService;
    private final ToolRegistry tools;
    private final ObjectMapper objectMapper;
    private final InputBindingResolver bindings;
    private final WorkflowProperties properties;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            task -> {
                Thread thread = new Thread(task, "workflow-node-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 复用 RAG、Agent、工具注册表、字段绑定和服务端预算。 */
    public WorkflowExecutor(RagService ragService, AgentService agentService, ToolRegistry tools,
                            ObjectMapper objectMapper, InputBindingResolver bindings,
                            WorkflowProperties properties) {
        this.ragService = ragService;
        this.agentService = agentService;
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.bindings = bindings;
        this.properties = properties;
    }

    /** 执行所选定义版本，供不需要中途持久化的调用者使用。 */
    public WorkflowRun execute(WorkflowDefinition definition, WorkflowRun running) {
        return execute(definition, running, progress -> { });
    }

    /** 串行执行选定路径，在每个节点状态变化时发布可持久化的运行记录。 */
    public WorkflowRun execute(WorkflowDefinition definition, WorkflowRun running,
                               Consumer<WorkflowRun> onProgress) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.totalTimeoutSeconds());
        Map<String, Node> nodes = new HashMap<>();
        definition.nodes().forEach(node -> nodes.put(node.id(), node));
        String current = definition.nodes().stream().filter(node -> node.type() == NodeType.START)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Workflow 缺少 START")).id();
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        List<NodeRun> traces = new ArrayList<>();

        while (true) {
            Node node = nodes.get(current);
            Instant started = Instant.now();
            try {
                if (node == null || traces.size() >= properties.maxNodeExecutions()) {
                    throw new IllegalArgumentException("Workflow 节点不存在或超过执行上限");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new WorkflowTimeout("WORKFLOW_TIMEOUT", "Workflow 总执行时间超限");
                }
                onProgress.accept(progress(running, traces, new NodeRun(current, NodeStatus.RUNNING,
                        started, null, Map.of(), null, null)));
                long nodeLimit = TimeUnit.SECONDS.toNanos(properties.nodeTimeoutSeconds());
                long available = deadline - System.nanoTime();
                Map<String, Object> output = within(Math.min(nodeLimit, available),
                        nodeLimit >= available, () -> executeNode(definition, node, nodes,
                                running.input(), outputs));
                if (System.nanoTime() >= deadline) {
                    throw new WorkflowTimeout("WORKFLOW_TIMEOUT", "Workflow 总执行时间超限");
                }
                String next = null;
                if (node.type() != NodeType.END) {
                    String from = current;
                    String branch = node.type() == NodeType.CONDITION
                            ? String.valueOf(output.get("result")) : null;
                    next = definition.edges().stream().filter(edge -> edge.from().equals(from)
                                    && Objects.equals(edge.branch(), branch))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                    "Workflow 节点缺少匹配的下一条边: " + from)).to();
                }
                outputs.put(node.id(), output);
                traces.add(new NodeRun(node.id(), NodeStatus.SUCCEEDED, started, Instant.now(),
                        summary(output), null, null));
                if (node.type() == NodeType.END) {
                    return finished(running, traces, (String) output.get("answer"),
                            citations(output.get("citations")));
                }
                onProgress.accept(progress(running, traces, null));
                current = next;
            } catch (RuntimeException error) {
                String code = error instanceof WorkflowTimeout timeout ? timeout.code()
                        : error instanceof IllegalArgumentException
                        && error.getMessage() != null && error.getMessage().startsWith("INPUT_ERROR")
                          ? "INPUT_ERROR" : "NODE_ERROR";
                String message = error instanceof WorkflowTimeout || error instanceof IllegalArgumentException
                        ? error.getMessage() : "节点执行失败";
                boolean timedOut = error instanceof WorkflowTimeout;
                traces.add(new NodeRun(current, timedOut ? NodeStatus.TIMED_OUT : NodeStatus.FAILED,
                        started, Instant.now(),
                        Map.of(), code, message));
                return failed(running, traces, timedOut ? Status.TIMED_OUT : Status.FAILED,
                        code, message);
            }
        }
    }

    /** 调用一个节点的现有实现；仅主执行线程写入节点输出。 */
    private Map<String, Object> executeNode(WorkflowDefinition definition, Node node,
                                            Map<String, Node> nodes, Map<String, Object> input,
                                            Map<String, Map<String, Object>> outputs) {
        return switch (node.type()) {
            case START -> start(definition, input);
            case RAG -> rag(node, input, outputs);
            case AGENT -> agent(node, nodes, input, outputs);
            case TOOL -> tool(node, input, outputs);
            case CONDITION -> condition(node, input, outputs);
            case END -> end(node, input, outputs);
        };
    }

    /** 在有界工作线程中调用节点，超时后中断并停止本次流程。 */
    private Map<String, Object> within(long nanos, boolean totalLimit,
                                       java.util.concurrent.Callable<Map<String, Object>> action) {
        if (nanos <= 0) {
            throw new WorkflowTimeout("WORKFLOW_TIMEOUT", "Workflow 总执行时间超限");
        }
        Future<Map<String, Object>> future;
        try {
            future = workers.submit(action);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Workflow 工作线程已满", e);
        }
        try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw totalLimit ? new WorkflowTimeout("WORKFLOW_TIMEOUT", "Workflow 总执行时间超限")
                    : new WorkflowTimeout("NODE_TIMEOUT", "Workflow 节点执行超时");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workflow 执行被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Workflow 节点执行失败", e.getCause());
        }
    }

    /** 生成仅包含已结束节点与当前执行中节点的进度快照。 */
    private WorkflowRun progress(WorkflowRun running, List<NodeRun> traces, NodeRun active) {
        List<NodeRun> snapshot = new ArrayList<>(traces);
        if (active != null) {
            snapshot.add(active);
        }
        return new WorkflowRun(running.id(), running.workflowId(), running.version(), Status.RUNNING,
                running.input(), List.copyOf(snapshot), null, List.of(), running.startedAt(),
                null, null, null);
    }

    /** 在应用关闭时释放节点执行线程。 */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    /** 表示节点或整个流程触及时间预算。 */
    private static final class WorkflowTimeout extends RuntimeException {
        private final String code;

        /** 保存公开的超时错误码及有限错误信息。 */
        private WorkflowTimeout(String code, String message) {
            super(message);
            this.code = code;
        }

        /** 返回节点或总期限对应的错误码。 */
        private String code() {
            return code;
        }
    }

    /** 验证所有已声明输入及固定文档范围，拒绝缺失、未知或空范围。 */
    private Map<String, Object> start(WorkflowDefinition definition, Map<String, Object> input) {
        Map<String, InputField> fields = definition.inputFields() == null
                ? Map.of() : definition.inputFields();
        for (String key : input.keySet()) {
            if (!fields.containsKey(key) && !List.of("question", "task", "documentIds").contains(key)) {
                throw new IllegalArgumentException("INPUT_ERROR: 未声明的运行字段: " + key);
            }
            if (input.get(key) == null) {
                throw new IllegalArgumentException("INPUT_ERROR: 运行字段不能为空: " + key);
            }
        }
        for (Map.Entry<String, InputField> field : fields.entrySet()) {
            if (field.getValue().required() && !input.containsKey(field.getKey())) {
                throw new IllegalArgumentException("INPUT_ERROR: 缺少必填字段: " + field.getKey());
            }
            if (input.containsKey(field.getKey())) {
                bindings.resolve(new Binding("input." + field.getKey(), null), field.getValue().type(),
                        input, Map.of());
            }
        }
        if (input.containsKey("documentIds")) {
            List<?> ids = (List<?>) bindings.resolve(new Binding("input.documentIds", null),
                    ValueType.STRING_LIST, input, Map.of());
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("INPUT_ERROR: documentIds 不能是空列表");
            }
        }
        return Map.of("status", "READY");
    }

    /** 解析问题与固定文档范围，然后调用已有 RagService。 */
    private Map<String, Object> rag(Node node, Map<String, Object> input,
                                    Map<String, Map<String, Object>> outputs) {
        String question = (String) bindings.resolve(node.inputBindings().get("question"),
                ValueType.STRING, input, outputs);
        Map<String, Object> config = node.config() == null ? Map.of() : node.config();
        RetrievalMode mode = config.containsKey("mode")
                ? RetrievalMode.valueOf((String) config.get("mode")) : null;
        Integer topK = config.containsKey("topK") ? ((Number) config.get("topK")).intValue() : null;
        Boolean rerank = (Boolean) config.get("rerank");
        RagResponse response = Objects.requireNonNull(ragService.ask(new RagRequest(
                question, documentScope(config, input), mode, topK, rerank)), "RAG 返回为空");
        if (response.status() == null || response.answer() == null || response.citations() == null) {
            throw new IllegalStateException("RAG 返回字段不完整");
        }
        return Map.of("status", response.status().name(), "answer", response.answer(),
                "citations", response.citations());
    }

    /** 组合显式任务和上游内容，让 Agent 在限定范围内自行取得知识引用。 */
    private Map<String, Object> agent(Node node, Map<String, Node> nodes, Map<String, Object> input,
                                      Map<String, Map<String, Object>> outputs) {
        Map<String, Object> config = node.config();
        Map<String, Binding> fields = node.inputBindings();
        String task = (String) bindings.resolve(fields.get("task"), ValueType.STRING, input, outputs);
        boolean knowledge = knowledgeSource(fields.get("task"), nodes)
                || knowledgeSource(fields.get("context"), nodes);
        StringBuilder combined = new StringBuilder();
        if (knowledge) {
            combined.append("先调用 rag_ask 在本次文档范围内核对上游事实并获取引用，再完成任务。\n");
        }
        if (config.containsKey("instruction")) {
            combined.append(config.get("instruction")).append('\n');
        }
        combined.append(task);
        if (fields.containsKey("context")) {
            combined.append("\n上游内容：\n")
                    .append(bindings.resolve(fields.get("context"), ValueType.STRING, input, outputs));
        }
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) config.get("allowedTools");
        Integer maxSteps = config.containsKey("maxSteps")
                ? ((Number) config.get("maxSteps")).intValue() : null;
        AgentResponse response = Objects.requireNonNull(agentService.run(new AgentRequest(
                combined.toString(), documentScope(config, input), allowed, maxSteps)), "Agent 返回为空");
        if (response.status() == null || response.answer() == null
                || response.citations() == null || response.steps() == null) {
            throw new IllegalStateException("Agent 返回字段不完整");
        }
        if (response.status() == AgentStatus.TIMEOUT) {
            throw new WorkflowTimeout("NODE_TIMEOUT", "Agent 节点执行超时");
        }
        if (response.status() != AgentStatus.COMPLETED
                && response.status() != AgentStatus.INSUFFICIENT_EVIDENCE) {
            throw new IllegalArgumentException("Agent 节点未完成: " + response.status());
        }
        if (knowledge && response.status() == AgentStatus.COMPLETED
                && response.citations().isEmpty()) {
            throw new IllegalArgumentException("Agent 未生成可核验的知识引用");
        }
        return Map.of("status", response.status().name(), "answer", response.answer(),
                "citations", response.citations(), "steps", response.steps());
    }

    /** 判断任务或上下文是否读取上游 RAG 的答案。 */
    private boolean knowledgeSource(Binding binding, Map<String, Node> nodes) {
        if (binding == null || binding.path() == null
                || !binding.path().matches("nodes\\.[A-Za-z][A-Za-z0-9_]*\\.answer")) {
            return false;
        }
        Node source = nodes.get(binding.path().split("\\.")[1]);
        return source != null && source.type() == NodeType.RAG;
    }

    /** 将确定性工具的显式参数交给既有注册表校验并执行。 */
    private Map<String, Object> tool(Node node, Map<String, Object> input,
                                     Map<String, Map<String, Object>> outputs) {
        String name = (String) node.config().get("tool");
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Binding> entry : node.inputBindings().entrySet()) {
            Object value = bindings.resolve(entry.getValue(), input, outputs);
            if (!(value instanceof Number) && !(value instanceof String text && !text.isBlank())) {
                throw new IllegalArgumentException("INPUT_ERROR: 工具参数类型不合法: " + entry.getKey());
            }
            arguments.put(entry.getKey(), value);
        }
        AgentContext context = new AgentContext("Workflow 工具节点 " + node.id(),
                documentScope(Map.of(), input), Set.of(name), 1);
        ToolResult result = Objects.requireNonNull(tools.execute(
                name, objectMapper.valueToTree(arguments), context), "工具返回为空");
        if (result.status() != ToolResult.ToolStatus.SUCCEEDED) {
            throw new IllegalArgumentException("Workflow 工具未成功: " + result.status());
        }
        return Map.of("status", result.status().name(), "content", result.content(),
                "citations", result.citations());
    }

    /** 对已完成节点字段做确定性比较，并返回选中的布尔分支。 */
    private Map<String, Object> condition(Node node, Map<String, Object> input,
                                          Map<String, Map<String, Object>> outputs) {
        Map<String, Object> config = node.config();
        Binding left = new Binding((String) config.get("left"), null);
        String op = (String) config.get("op");
        if ("EXISTS".equals(op)) {
            try {
                bindings.resolve(left, input, outputs);
                return Map.of("result", true);
            } catch (IllegalArgumentException missing) {
                if (missing.getMessage() == null
                        || !missing.getMessage().startsWith("INPUT_ERROR: 绑定字段缺失:")) {
                    throw missing;
                }
                return Map.of("result", false);
            }
        }
        Object value = bindings.resolve(left, input, outputs);
        Object right = config.get("right");
        boolean equal = value instanceof Number a && right instanceof Number b
                ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0
                : Objects.equals(value, right);
        if (!"EQUALS".equals(op) && !"NOT_EQUALS".equals(op)) {
            throw new IllegalArgumentException("非法条件运算符: " + op);
        }
        return Map.of("result", "EQUALS".equals(op) ? equal : !equal);
    }

    /** 取运行时的固定范围，并仅允许节点在该范围内继续收窄。 */
    private List<String> documentScope(Map<String, Object> config, Map<String, Object> input) {
        List<String> scope = null;
        if (input.containsKey("documentIds")) {
            List<?> value = (List<?>) bindings.resolve(new Binding("input.documentIds", null),
                    ValueType.STRING_LIST, input, Map.of());
            scope = value.stream().map(String.class::cast).distinct().toList();
        }
        if (!config.containsKey("documentIds")) {
            return scope;
        }
        if (scope == null) {
            throw new IllegalArgumentException("INPUT_ERROR: 收窄文档范围需要运行时 documentIds");
        }
        List<?> restricted = (List<?>) config.get("documentIds");
        List<String> selected = scope.stream().filter(restricted::contains).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("INPUT_ERROR: 节点与运行时文档范围没有交集");
        }
        return selected;
    }

    /** 只按已验证的显式绑定确定最终答案和真实引用。 */
    private Map<String, Object> end(Node node, Map<String, Object> input,
                                    Map<String, Map<String, Object>> outputs) {
        Map<String, Binding> fields = node.outputBindings();
        String answer = (String) bindings.resolve(fields.get("answer"), ValueType.STRING, input, outputs);
        List<RagCitation> citations = fields.containsKey("citations")
                ? citations(bindings.resolve(fields.get("citations"), ValueType.CITATIONS, input, outputs))
                : List.of();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answer", answer);
        output.put("citations", citations);
        if (fields.containsKey("status")) {
            output.put("status", bindings.resolve(fields.get("status"), ValueType.STRING, input, outputs));
        }
        return output;
    }

    /** 将经过类型检查的引用列表转为最终结果列表。 */
    private List<RagCitation> citations(Object value) {
        return ((List<?>) value).stream().map(RagCitation.class::cast).toList();
    }

    /** 仅保存节点摘要，不把完整答案和文档片段复制到每条轨迹。 */
    private Map<String, Object> summary(Map<String, Object> output) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (output.containsKey("status")) {
            result.put("status", output.get("status"));
        }
        if (output.containsKey("answer")) {
            String answer = (String) output.get("answer");
            result.put("answer", answer.substring(0, Math.min(answer.length(), properties.maxOutputChars())));
            result.put("citationCount", citations(output.get("citations")).size());
        }
        if (output.containsKey("content")) {
            String content = (String) output.get("content");
            result.put("content", content.substring(0, Math.min(content.length(), properties.maxOutputChars())));
        }
        if (output.containsKey("result")) {
            result.put("result", output.get("result"));
        }
        if (output.containsKey("steps")) {
            @SuppressWarnings("unchecked")
            List<AgentStep> steps = (List<AgentStep>) output.get("steps");
            result.put("steps", steps.stream().limit(4).map(step -> Map.of(
                            "index", step.index(), "tool", step.tool(), "status", step.status().name(),
                            "summary", step.summary().substring(0,
                                    Math.min(step.summary().length(), Math.min(160, properties.maxOutputChars())))))
                    .toList());
        }
        return result;
    }

    /** 构造结束于 END 的成功运行结果。 */
    private WorkflowRun finished(WorkflowRun running, List<NodeRun> traces, String answer,
                                 List<RagCitation> citations) {
        return new WorkflowRun(running.id(), running.workflowId(), running.version(), Status.SUCCEEDED,
                running.input(), List.copyOf(traces), answer, citations,
                running.startedAt(), Instant.now(), null, null);
    }

    /** 构造包含失败节点和错误位置的运行结果。 */
    private WorkflowRun failed(WorkflowRun running, List<NodeRun> traces, Status status,
                               String code, String message) {
        return new WorkflowRun(running.id(), running.workflowId(), running.version(), status,
                running.input(), List.copyOf(traces), null, List.of(),
                running.startedAt(), Instant.now(), code, message);
    }
}
