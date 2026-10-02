package com.junhaohan.knowledgeingestion.workflow.binding;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Binding;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.InputField;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.Node;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.NodeType;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 只解析运行输入或已完成节点的结构化字段，不执行表达式。 */
@Component
public class InputBindingResolver {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** 在保存定义前检查绑定来源、上游关系与字段类型。 */
    public ValueType typeOf(Binding binding, Map<String, InputField> inputFields,
                            Map<String, Node> nodes, Set<String> ancestors) {
        if (binding == null || (binding.path() == null) == (binding.value() == null)) {
            throw new IllegalArgumentException("绑定必须且只能提供 path 或非空 value");
        }
        if (binding.path() == null) {
            return typeOfConstant(binding.value());
        }
        PathRef path = parse(binding.path());
        if (path.nodeId() == null) {
            return inputType(path.field(), inputFields);
        }
        if (!ancestors.contains(path.nodeId())) {
            throw new IllegalArgumentException("绑定引用的节点不是必经上游: " + path.nodeId());
        }
        Node source = nodes.get(path.nodeId());
        if (source == null || source.type() == null) {
            throw new IllegalArgumentException("绑定引用了不存在的节点: " + path.nodeId());
        }
        return outputType(source.type(), path.field());
    }

    /** 在运行时读取确切字段，缺失或空值按输入错误终止。 */
    public Object resolve(Binding binding, Map<String, Object> input,
                          Map<String, Map<String, Object>> nodeOutputs) {
        if (binding == null || (binding.path() == null) == (binding.value() == null)) {
            throw new IllegalArgumentException("INPUT_ERROR: 绑定缺少唯一来源");
        }
        if (binding.path() == null) {
            return binding.value();
        }
        PathRef path = parse(binding.path());
        Map<String, Object> source = path.nodeId() == null
                ? input : nodeOutputs.get(path.nodeId());
        if (source == null || !source.containsKey(path.field()) || source.get(path.field()) == null) {
            throw new IllegalArgumentException("INPUT_ERROR: 绑定字段缺失: " + binding.path());
        }
        return source.get(path.field());
    }

    /** 读取绑定并校验实际值类型，避免外部输入绕过定义期的声明类型。 */
    public Object resolve(Binding binding, ValueType expected, Map<String, Object> input,
                          Map<String, Map<String, Object>> nodeOutputs) {
        Object value = resolve(binding, input, nodeOutputs);
        boolean matches = switch (expected) {
            case STRING -> value instanceof String text && !text.isBlank();
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING_LIST -> value instanceof List<?> list
                    && list.stream().allMatch(item -> item instanceof String text && !text.isBlank());
            case CITATIONS -> value instanceof List<?> list
                    && list.stream().allMatch(RagCitation.class::isInstance);
        };
        if (!matches) {
            throw new IllegalArgumentException("INPUT_ERROR: 绑定值的实际类型或内容不合法: "
                    + (binding.path() == null ? "常量" : binding.path()));
        }
        return value;
    }

    /** 检查运行输入或常量的基础类型。 */
    public ValueType typeOfConstant(Object value) {
        if (value instanceof String) {
            return ValueType.STRING;
        }
        if (value instanceof Number) {
            return ValueType.NUMBER;
        }
        if (value instanceof Boolean) {
            return ValueType.BOOLEAN;
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return ValueType.STRING_LIST;
        }
        throw new IllegalArgumentException("绑定常量只允许字符串、数字、布尔值或字符串数组");
    }

    /** 查询声明的输入字段类型；常用任务与文档范围有固定类型。 */
    private ValueType inputType(String field, Map<String, InputField> declaredFields) {
        ValueType builtin = switch (field) {
            case "question", "task" -> ValueType.STRING;
            case "documentIds" -> ValueType.STRING_LIST;
            default -> null;
        };
        InputField declared = declaredFields == null ? null : declaredFields.get(field);
        if (declared != null && declared.type() != null
                && (builtin == null || builtin == declared.type())) {
            return declared.type();
        }
        if (builtin != null && declared == null) {
            return builtin;
        }
        throw new IllegalArgumentException("输入字段未声明或类型冲突: " + field);
    }

    /** 只开放节点处理器确实会产生的结构化输出。 */
    private ValueType outputType(NodeType nodeType, String field) {
        return switch (nodeType) {
            case RAG, AGENT -> switch (field) {
                case "status", "answer" -> ValueType.STRING;
                case "citations" -> ValueType.CITATIONS;
                default -> throw new IllegalArgumentException("节点没有输出字段: " + field);
            };
            case TOOL -> switch (field) {
                case "status", "content" -> ValueType.STRING;
                case "citations" -> ValueType.CITATIONS;
                default -> throw new IllegalArgumentException("工具没有输出字段: " + field);
            };
            case CONDITION -> {
                if (!"result".equals(field)) {
                    throw new IllegalArgumentException("条件节点没有输出字段: " + field);
                }
                yield ValueType.BOOLEAN;
            }
            case START, END -> throw new IllegalArgumentException("该节点没有可绑定输出: " + nodeType);
        };
    }

    /** 严格解析 input.x 或 nodes.nodeId.field 两种字段路径。 */
    private PathRef parse(String path) {
        if (path == null) {
            throw new IllegalArgumentException("绑定路径不能为空");
        }
        String[] parts = path.split("\\.", -1);
        if (parts.length == 2 && "input".equals(parts[0]) && valid(parts[1])) {
            return new PathRef(null, parts[1]);
        }
        if (parts.length == 3 && "nodes".equals(parts[0])
                && valid(parts[1]) && valid(parts[2])) {
            return new PathRef(parts[1], parts[2]);
        }
        throw new IllegalArgumentException("非法绑定路径: " + path);
    }

    /** 限制字段与节点标识的字符集合。 */
    private boolean valid(String segment) {
        return SEGMENT.matcher(segment).matches();
    }

    /** 保存解析后的节点及字段名。 */
    private record PathRef(String nodeId, String field) {
    }
}