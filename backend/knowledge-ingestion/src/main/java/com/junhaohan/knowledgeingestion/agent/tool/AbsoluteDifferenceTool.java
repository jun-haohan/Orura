package com.junhaohan.knowledgeingestion.agent.tool;

import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 对两个十进制数计算无方向的绝对差。 */
@Component
public class AbsoluteDifferenceTool implements AgentTool {

    /** 返回工具名称。 */
    @Override
    public String name() {
        return "absolute_difference";
    }

    /** 告诉模型何时使用绝对差。 */
    @Override
    public String description() {
        return "计算两个数相差多少，自动取差的绝对值；入参 a、b 是不带单位的数字字符串";
    }

    /** 声明两个必填的十进制数字字符串。 */
    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "string", "description", "第一个十进制数，不带单位"),
                        "b", Map.of("type", "string", "description", "第二个十进制数，不带单位")
                ),
                "required", List.of("a", "b"),
                "additionalProperties", false
        );
    }

    /** 拒绝缺字段、额外字段及非十进制数字。 */
    @Override
    public void validateArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 2) {
            throw new IllegalArgumentException("absolute_difference 只接受 a、b 两个参数");
        }
        decimalOf(arguments.get("a"));
        decimalOf(arguments.get("b"));
    }

    /** 用 BigDecimal 返回两个数的非负差值。 */
    @Override
    public ToolResult execute(JsonNode arguments, AgentContext context) {
        validateArguments(arguments);
        BigDecimal a = decimalOf(arguments.get("a"));
        BigDecimal b = decimalOf(arguments.get("b"));
        String result = a.subtract(b).abs().stripTrailingZeros().toPlainString();
        return new ToolResult(ToolResult.ToolStatus.SUCCEEDED,
                a.toPlainString() + " 与 " + b.toPlainString() + " 的绝对差 = " + result, List.of());
    }

    /** 校验数字字符串或 JSON 数字并转为十进制数。 */
    private BigDecimal decimalOf(JsonNode node) {
        if (node == null) {
            throw new IllegalArgumentException("绝对差参数不能为空");
        }
        String value = node.isTextual() ? node.asString().strip() : node.toString();
        if (value.length() > 32 || !value.matches("-?\\d+(?:\\.\\d+)?")) {
            throw new IllegalArgumentException("绝对差参数不是有效十进制数");
        }
        return new BigDecimal(value);
    }
}
