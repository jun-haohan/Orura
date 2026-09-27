package com.junhaohan.knowledgeingestion.agent.tool;

import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.runtime.AgentContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 用有限的四则表达式解析器执行确定性计算。 */
@Component
public class CalculatorTool implements AgentTool {

    private static final int MAX_EXPRESSION_LENGTH = 256;
    private static final int MAX_DEPTH = 32;
    private static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_UP);

    /** 返回计算工具的固定名称。 */
    @Override
    public String name() {
        return "calculator";
    }

    /** 说明可用的运算及精度。 */
    @Override
    public String description() {
        return "计算加减乘除及括号，按 16 位有效数字和 HALF_UP 舍入";
    }

    /** 声明唯一允许的表达式参数。 */
    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("expression", Map.of("type", "string", "description", "四则运算表达式")),
                "required", List.of("expression"),
                "additionalProperties", false
        );
    }

    /** 检查参数结构并验证表达式可安全求值。 */
    @Override
    public void validateArguments(JsonNode arguments) {
        String expression = expressionOf(arguments);
        new Parser(expression).parse();
    }

    /** 解析表达式并返回无文档引用的数值结果。 */
    @Override
    public ToolResult execute(JsonNode arguments, AgentContext context) {
        String expression = expressionOf(arguments);
        BigDecimal value = new Parser(expression).parse();
        String result = value.stripTrailingZeros().toPlainString();
        return new ToolResult(ToolResult.ToolStatus.SUCCEEDED,
                expression + " = " + result, List.of());
    }

    /** 提取唯一合法的字符串表达式参数。 */
    private String expressionOf(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1
                || arguments.get("expression") == null || !arguments.get("expression").isTextual()) {
            throw new IllegalArgumentException("calculator 只接受字符串 expression");
        }
        String expression = arguments.get("expression").asString().strip();
        if (expression.isEmpty() || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("表达式长度必须在 1 到 256 个字符之间");
        }
        return expression;
    }

    /** 按运算符优先级解析表达式，不执行任意程序代码。 */
    private static final class Parser {
        private final String input;
        private int index;

        /** 记录待解析的表达式。 */
        private Parser(String input) {
            this.input = input;
        }

        /** 完整解析输入，拒绝多余字符。 */
        private BigDecimal parse() {
            BigDecimal value = parseExpression(0);
            skipWhitespace();
            if (index != input.length()) {
                throw new IllegalArgumentException("表达式包含不支持的字符");
            }
            return value;
        }

        /** 解析加法和减法。 */
        private BigDecimal parseExpression(int depth) {
            BigDecimal value = parseTerm(depth);
            while (true) {
                if (match('+')) {
                    value = value.add(parseTerm(depth), PRECISION);
                } else if (match('-')) {
                    value = value.subtract(parseTerm(depth), PRECISION);
                } else {
                    return value;
                }
            }
        }

        /** 解析乘法和除法，并拒绝除零。 */
        private BigDecimal parseTerm(int depth) {
            BigDecimal value = parseFactor(depth);
            while (true) {
                if (match('*')) {
                    value = value.multiply(parseFactor(depth), PRECISION);
                } else if (match('/')) {
                    BigDecimal divisor = parseFactor(depth);
                    if (divisor.signum() == 0) {
                        throw new IllegalArgumentException("不能除零");
                    }
                    value = value.divide(divisor, PRECISION);
                } else {
                    return value;
                }
            }
        }

        /** 解析正负号、括号或十进制数字。 */
        private BigDecimal parseFactor(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("表达式嵌套过深");
            }
            if (match('+')) {
                return parseFactor(depth + 1);
            }
            if (match('-')) {
                return parseFactor(depth + 1).negate();
            }
            if (match('(')) {
                BigDecimal value = parseExpression(depth + 1);
                if (!match(')')) {
                    throw new IllegalArgumentException("表达式括号未闭合");
                }
                return value;
            }
            return parseNumber();
        }

        /** 读取一个不带科学计数法的十进制数字。 */
        private BigDecimal parseNumber() {
            skipWhitespace();
            int start = index;
            boolean hasDigit = false;
            while (index < input.length() && isDigit(input.charAt(index))) {
                index++;
                hasDigit = true;
            }
            if (index < input.length() && input.charAt(index) == '.') {
                index++;
                while (index < input.length() && isDigit(input.charAt(index))) {
                    index++;
                    hasDigit = true;
                }
            }
            if (!hasDigit) {
                throw new IllegalArgumentException("表达式缺少数字");
            }
            return new BigDecimal(input.substring(start, index));
        }

        /** 判断字符是否为 ASCII 数字。 */
        private boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        /** 跳过表达式中的空白字符。 */
        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        /** 跳过空白后消费指定运算符。 */
        private boolean match(char expected) {
            skipWhitespace();
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }
    }
}