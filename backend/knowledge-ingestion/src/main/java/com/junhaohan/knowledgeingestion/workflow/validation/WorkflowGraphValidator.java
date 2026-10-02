package com.junhaohan.knowledgeingestion.workflow.validation;

import com.junhaohan.knowledgeingestion.agent.config.AgentProperties;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.retrieval.model.RetrievalMode;
import com.junhaohan.knowledgeingestion.workflow.binding.InputBindingResolver;
import com.junhaohan.knowledgeingestion.workflow.config.WorkflowProperties;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Binding;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Edge;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.InputField;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Node;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.NodeType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.SaveRequest;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 在保存前验证单路径、可分支 DAG 的形状、节点契约和字段来源。 */
@Component
public class WorkflowGraphValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> DETERMINISTIC_TOOLS = Set.of("calculator", "absolute_difference");

    private final InputBindingResolver bindings;
    private final WorkflowProperties properties;
    private final ToolRegistry registry;
    private final AgentProperties agentProperties;
    private final int maxRagTopK;

    /** 注入已有工具注册表与两层服务端预算。 */
    public WorkflowGraphValidator(InputBindingResolver bindings, WorkflowProperties properties,
                                  ToolRegistry registry, AgentProperties agentProperties,
                                  @Value("${rag.retrieval.max-top-k:10}") int maxRagTopK) {
        this.bindings = bindings;
        this.properties = properties;
        this.registry = registry;
        this.agentProperties = agentProperties;
        this.maxRagTopK = maxRagTopK;
    }

    /** 拒绝不合法的流程定义，错误信息定位到具体节点或字段。 */
    public void validate(SaveRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().length() > 100) {
            throw new IllegalArgumentException("流程名称长度必须为 1 到 100 个字符");
        }
        validateInputFields(request.inputFields());
        if (request.nodes() == null || request.nodes().size() < 2
                || request.nodes().size() > properties.maxNodes() || request.edges() == null) {
            throw new IllegalArgumentException("Workflow 节点或边为空，或超过服务端节点上限");
        }

        Map<String, Node> nodes = new HashMap<>();
        Node start = null;
        int ends = 0;
        for (Node node : request.nodes()) {
            if (node == null || !validId(node.id()) || node.type() == null
                    || nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Workflow 节点 ID 重复、缺失或类型不合法");
            }
            if (node.type() == NodeType.START) {
                if (start != null) {
                    throw new IllegalArgumentException("Workflow 只能有一个 START");
                }
                start = node;
            }
            if (node.type() == NodeType.END) {
                ends++;
            }
        }
        if (start == null || ends == 0) {
            throw new IllegalArgumentException("Workflow 必须有一个 START 和至少一个 END");
        }

        Map<String, String> parent = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        for (Edge edge : request.edges()) {
            if (edge == null || !nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                throw new IllegalArgumentException("Workflow 边的端点不存在");
            }
            if (parent.putIfAbsent(edge.to(), edge.from()) != null) {
                throw new IllegalArgumentException("节点不允许多个入边或分支合流: " + edge.to());
            }
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        for (Node node : request.nodes()) {
            List<Edge> edges = outgoing.getOrDefault(node.id(), List.of());
            if (node.type() == NodeType.START && parent.containsKey(node.id())) {
                throw new IllegalArgumentException("START 不允许有入边");
            }
            if (node.type() != NodeType.START && !parent.containsKey(node.id())) {
                throw new IllegalArgumentException("节点必须恰好有一个入边: " + node.id());
            }
            if (node.type() == NodeType.END) {
                if (!edges.isEmpty()) {
                    throw new IllegalArgumentException("END 不允许有出边: " + node.id());
                }
            } else if (node.type() == NodeType.CONDITION) {
                if (edges.size() != 2
                        || !("true".equals(edges.get(0).branch()) && "false".equals(edges.get(1).branch())
                        || "false".equals(edges.get(0).branch()) && "true".equals(edges.get(1).branch()))) {
                    throw new IllegalArgumentException("CONDITION 必须有 true、false 两条出边: " + node.id());
                }
            } else if (edges.size() != 1 || edges.get(0).branch() != null) {
                throw new IllegalArgumentException("普通节点必须恰好有一条无分支出边: " + node.id());
            }
        }

        Set<String> inspected = new HashSet<>();
        for (Node node : request.nodes()) {
            if (!inspected.contains(node.id())) {
                walk(node.id(), outgoing, new HashSet<>(), inspected);
            }
        }
        Set<String> reached = new HashSet<>();
        walk(start.id(), outgoing, new HashSet<>(), reached);
        if (reached.size() != nodes.size()) {
            String missing = nodes.keySet().stream().filter(id -> !reached.contains(id))
                    .findFirst().orElse("unknown");
            throw new IllegalArgumentException("Workflow 存在从 START 不可达的节点: " + missing);
        }
        for (Node node : request.nodes()) {
            Set<String> ancestors = ancestorsOf(node.id(), parent);
            if (ancestors.size() + 1 > properties.maxNodeExecutions()) {
                throw new IllegalArgumentException("流程路径超过单次节点执行上限: " + node.id());
            }
            validateNode(node, nodes, optional(request.inputFields()), ancestors);
        }
    }

    /** 检查声明字段及内置输入字段的类型是否冲突。 */
    private void validateInputFields(Map<String, InputField> fields) {
        for (Map.Entry<String, InputField> entry : optional(fields).entrySet()) {
            if (!validId(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().type() == null
                    || entry.getValue().type() == ValueType.CITATIONS) {
                throw new IllegalArgumentException("非法运行输入字段: " + entry.getKey());
            }
            bindings.typeOf(new Binding("input." + entry.getKey(), null),
                    fields, Map.of(), Set.of());
        }
    }

    /** 用深度优先搜索拒绝循环并记录从起点可达的节点。 */
    private void walk(String id, Map<String, List<Edge>> outgoing,
                      Set<String> visiting, Set<String> reached) {
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Workflow 存在循环: " + id);
        }
        if (reached.add(id)) {
            for (Edge edge : outgoing.getOrDefault(id, List.of())) {
                walk(edge.to(), outgoing, visiting, reached);
            }
        }
        visiting.remove(id);
    }

    /** 取得当前节点每条执行路径上都已经完成的节点。 */
    private Set<String> ancestorsOf(String id, Map<String, String> parent) {
        Set<String> ancestors = new HashSet<>();
        String current = parent.get(id);
        while (current != null && ancestors.add(current)) {
            current = parent.get(current);
        }
        if (current != null) {
            throw new IllegalArgumentException("Workflow 存在循环: " + id);
        }
        return ancestors;
    }

    /** 按节点类型检查配置、允许的绑定名与静态参数类型。 */
    private void validateNode(Node node, Map<String, Node> nodes,
                              Map<String, InputField> inputs, Set<String> ancestors) {
        Map<String, Binding> in = optional(node.inputBindings());
        Map<String, Binding> out = optional(node.outputBindings());
        Map<String, Object> config = optional(node.config());
        switch (node.type()) {
            case START -> {
                onlyKeys(in, Set.of(), node.id());
                onlyKeys(out, Set.of(), node.id());
                onlyKeys(config, Set.of(), node.id());
            }
            case RAG -> {
                onlyKeys(in, Set.of("question"), node.id());
                onlyKeys(out, Set.of(), node.id());
                onlyKeys(config, Set.of("mode", "topK", "rerank", "documentIds"), node.id());
                require(in, "question", ValueType.STRING, inputs, nodes, ancestors, node.id());
                if (config.containsKey("mode")) {
                    try {
                        RetrievalMode.valueOf(String.valueOf(config.get("mode")));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("RAG 检索模式不合法: " + node.id(), e);
                    }
                }
                if (config.containsKey("topK")) {
                    positiveInt(config.get("topK"), 1, maxRagTopK, node.id());
                }
                if (config.containsKey("rerank") && !(config.get("rerank") instanceof Boolean)) {
                    throw new IllegalArgumentException("RAG rerank 必须是布尔值: " + node.id());
                }
                validateDocumentIds(config, node.id());
            }
            case AGENT -> {
                onlyKeys(in, Set.of("task", "context"), node.id());
                onlyKeys(out, Set.of(), node.id());
                onlyKeys(config, Set.of("allowedTools", "maxSteps", "instruction", "documentIds"), node.id());
                require(in, "task", ValueType.STRING, inputs, nodes, ancestors, node.id());
                if (in.containsKey("context")) {
                    require(in, "context", ValueType.STRING, inputs, nodes, ancestors, node.id());
                }
                List<String> allowed = toolNames(config.get("allowedTools"), node.id());
                registry.allowedTools(allowed);
                if ((knowledgeSource(in.get("task"), nodes)
                        || knowledgeSource(in.get("context"), nodes)) && !allowed.contains("rag_ask")) {
                    throw new IllegalArgumentException("使用上游知识内容的 Agent 需允许 rag_ask 自行获取引用: " + node.id());
                }
                if (config.containsKey("maxSteps")) {
                    positiveInt(config.get("maxSteps"), 1, agentProperties.maxSteps(), node.id());
                }
                if (config.containsKey("instruction") && (!(config.get("instruction") instanceof String text)
                        || text.length() > agentProperties.maxTaskChars())) {
                    throw new IllegalArgumentException("Agent 指令类型或长度不合法: " + node.id());
                }
                validateDocumentIds(config, node.id());
            }
            case TOOL -> {
                onlyKeys(out, Set.of(), node.id());
                onlyKeys(config, Set.of("tool"), node.id());
                String name = String.valueOf(config.get("tool"));
                if (!DETERMINISTIC_TOOLS.contains(name)) {
                    throw new IllegalArgumentException("Workflow TOOL 只允许确定性工具: " + node.id());
                }
                registry.allowedTools(List.of(name));
                Set<String> expected = "calculator".equals(name) ? Set.of("expression") : Set.of("a", "b");
                onlyKeys(in, expected, node.id());
                for (String argument : expected) {
                    ValueType actual = typeOf(in.get(argument), inputs, nodes, ancestors, node.id());
                    if (actual != ValueType.STRING && !("absolute_difference".equals(name)
                            && actual == ValueType.NUMBER)) {
                        throw new IllegalArgumentException("工具参数类型不合法: " + node.id() + "." + argument);
                    }
                }
            }
            case CONDITION -> {
                onlyKeys(in, Set.of(), node.id());
                onlyKeys(out, Set.of(), node.id());
                onlyKeys(config, Set.of("left", "op", "right"), node.id());
                if (!(config.get("left") instanceof String path) || !path.startsWith("nodes.")) {
                    throw new IllegalArgumentException("CONDITION 只允许读取上游节点字段: " + node.id());
                }
                ValueType left = bindings.typeOf(new Binding(path, null), inputs, nodes, ancestors);
                String op = String.valueOf(config.get("op"));
                if (!Set.of("EQUALS", "NOT_EQUALS", "EXISTS").contains(op)) {
                    throw new IllegalArgumentException("条件运算符不合法: " + node.id());
                }
                if ("EXISTS".equals(op)) {
                    if (config.containsKey("right")) {
                        throw new IllegalArgumentException("EXISTS 条件不能有 right: " + node.id());
                    }
                } else if (!config.containsKey("right")
                        || bindings.typeOfConstant(config.get("right")) != left) {
                    throw new IllegalArgumentException("条件比较值类型不匹配: " + node.id());
                }
            }
            case END -> {
                onlyKeys(in, Set.of(), node.id());
                onlyKeys(config, Set.of(), node.id());
                onlyKeys(out, Set.of("answer", "citations", "status"), node.id());
                require(out, "answer", ValueType.STRING, inputs, nodes, ancestors, node.id());
                if (out.containsKey("status")) {
                    require(out, "status", ValueType.STRING, inputs, nodes, ancestors, node.id());
                }
                if (out.containsKey("citations")) {
                    Binding citations = out.get("citations");
                    if (!(citations != null && citations.path() == null
                            && citations.value() instanceof List<?> list && list.isEmpty())) {
                        require(out, "citations", ValueType.CITATIONS, inputs, nodes, ancestors, node.id());
                    }
                }
                Binding answer = out.get("answer");
                if (answer != null && answer.path() != null && answer.path().startsWith("nodes.")
                        && answer.path().endsWith(".answer")) {
                    String sourceId = answer.path().split("\\.")[1];
                    Node source = nodes.get(sourceId);
                    if (source != null && (source.type() == NodeType.RAG || source.type() == NodeType.AGENT)) {
                        Binding citations = out.get("citations");
                        if (citations == null || !("nodes." + sourceId + ".citations")
                                .equals(citations.path())) {
                            throw new IllegalArgumentException("知识答案必须绑定同一节点的引用: " + node.id());
                        }
                    }
                }
            }
        }
    }

    /** 检查必填绑定存在、类型一致且常量不为空白。 */
    private void require(Map<String, Binding> values, String key, ValueType expected,
                         Map<String, InputField> inputs, Map<String, Node> nodes,
                         Set<String> ancestors, String nodeId) {
        Binding binding = values.get(key);
        if (typeOf(binding, inputs, nodes, ancestors, nodeId) != expected
                || binding.path() == null && binding.value() instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("必填字段类型或内容不合法: " + nodeId + "." + key);
        }
    }

    /** 返回一个绑定的类型，并为错误附加当前节点标识。 */
    private ValueType typeOf(Binding binding, Map<String, InputField> inputs,
                             Map<String, Node> nodes, Set<String> ancestors, String nodeId) {
        try {
            return bindings.typeOf(binding, inputs, nodes, ancestors);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("节点 " + nodeId + " 绑定错误: " + e.getMessage(), e);
        }
    }

    /** 拒绝节点契约之外的输入、输出和配置键。 */
    private void onlyKeys(Map<String, ?> fields, Set<String> allowed, String nodeId) {
        for (String key : fields.keySet()) {
            if (key == null || !allowed.contains(key)) {
                throw new IllegalArgumentException("节点 " + nodeId + " 包含未知字段: " + key);
            }
        }
    }

    /** 仅接受已注册工具名称组成的非空白名单。 */
    private List<String> toolNames(Object value, String nodeId) {
        if (!(value instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(item -> !(item instanceof String name) || name.isBlank())) {
            throw new IllegalArgumentException("Agent allowedTools 必须是非空工具名数组: " + nodeId);
        }
        return list.stream().map(String.class::cast).toList();
    }

    /** 校验节点收窄文档范围的常量，不允许空列表。 */
    private void validateDocumentIds(Map<String, Object> config, String nodeId) {
        if (!config.containsKey("documentIds")) {
            return;
        }
        Object value = config.get("documentIds");
        if (!(value instanceof List<?> ids) || ids.isEmpty()
                || ids.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("节点 documentIds 必须是非空文档 ID 数组: " + nodeId);
        }
    }

    /** 限定客户端提供的整数范围。 */
    private int positiveInt(Object value, int min, int max, String nodeId) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) {
            throw new IllegalArgumentException("节点预算超出服务端范围: " + nodeId);
        }
        return number.intValue();
    }

    /** 判断节点与输入字段名称是否符合受限路径语法。 */
    private boolean validId(String id) {
        return id != null && id.length() <= 64 && IDENTIFIER.matcher(id).matches();
    }

    /** 将未提交的可选字段视为空映射。 */
    private <K, V> Map<K, V> optional(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    /** 判断绑定是否读取 RAG 节点产生的知识答案。 */
    private boolean knowledgeSource(Binding binding, Map<String, Node> nodes) {
        if (binding == null || binding.path() == null
                || !binding.path().matches("nodes\\.[A-Za-z][A-Za-z0-9_]*\\.answer")) {
            return false;
        }
        Node source = nodes.get(binding.path().split("\\.")[1]);
        return source != null && source.type() == NodeType.RAG;
    }
}
