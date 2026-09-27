package com.junhaohan.knowledgeingestion.agent.runtime;

import com.junhaohan.knowledgeingestion.agent.config.AgentProperties;
import com.junhaohan.knowledgeingestion.agent.citation.CitationCollector;
import com.junhaohan.knowledgeingestion.agent.llm.AgentModelClient;
import com.junhaohan.knowledgeingestion.agent.llm.AgentModelClient.ToolExchange;
import com.junhaohan.knowledgeingestion.agent.model.AgentAction;
import com.junhaohan.knowledgeingestion.agent.model.AgentResponse;
import com.junhaohan.knowledgeingestion.agent.model.AgentStatus;
import com.junhaohan.knowledgeingestion.agent.model.AgentStep;
import com.junhaohan.knowledgeingestion.agent.model.AgentStep.StepStatus;
import com.junhaohan.knowledgeingestion.agent.model.ToolResult;
import com.junhaohan.knowledgeingestion.agent.tool.ToolRegistry;
import com.junhaohan.knowledgeingestion.rag.llm.LlmClientException;
import com.junhaohan.knowledgeingestion.rag.service.CitationValidator;
import com.junhaohan.knowledgeingestion.rag.service.CitationValidator.ValidatedAnswer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** 按执行预算循环调用模型与工具，并验证最终回答引用。 */
@Component
public class AgentExecutor {

    private static final String NO_EVIDENCE = "无法根据提供的资料确认。";

    private final AgentModelClient modelClient;
    private final ToolRegistry registry;
    private final CitationValidator citationValidator;
    private final AgentProperties properties;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            task -> {
                Thread thread = new Thread(task, "agent-runtime-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 注入动作客户端、工具注册表、引用校验器与输出预算。 */
    public AgentExecutor(AgentModelClient modelClient, ToolRegistry registry,
                         CitationValidator citationValidator, AgentProperties properties) {
        this.modelClient = modelClient;
        this.registry = registry;
        this.citationValidator = citationValidator;
        this.properties = properties;
    }

    /** 最多执行 maxSteps 次工具，并把每轮模型请求计入总期限。 */
    public AgentResponse run(AgentContext context) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.totalTimeoutSeconds());
        List<ToolExchange> exchanges = new ArrayList<>();
        List<AgentStep> steps = new ArrayList<>();
        Set<CallSignature> seenCalls = new HashSet<>();
        CitationCollector citations = new CitationCollector();

        for (int decision = 0; decision <= context.maxSteps(); decision++) {
            final AgentAction action;
            try {
                long timeout = Math.min(remaining(deadline),
                        TimeUnit.SECONDS.toNanos(properties.modelTimeoutSeconds()));
                action = within(timeout, () -> modelClient.decide(
                        context, List.copyOf(exchanges), Duration.ofNanos(timeout)));
            } catch (LlmClientException e) {
                return modelFailure(e, steps);
            } catch (RuntimeException e) {
                return new AgentResponse(AgentStatus.MODEL_FAILED,
                        "模型调用失败", List.of(), List.copyOf(steps));
            }

            if (action instanceof AgentAction.FinalAnswer answer) {
                if (steps.isEmpty()) {
                    return new AgentResponse(AgentStatus.MODEL_FAILED,
                            "模型未选择工具", List.of(), List.of());
                }
                if (System.nanoTime() >= deadline) {
                    return timedOut(steps);
                }
                return finish(answer.answer(), citations, steps);
            }
            if (!(action instanceof AgentAction.CallTool call)) {
                return new AgentResponse(AgentStatus.MODEL_FAILED,
                        "模型未返回有效动作", List.of(), List.copyOf(steps));
            }
            if (decision == context.maxSteps()) {
                return new AgentResponse(AgentStatus.MAX_STEPS_REACHED,
                        "已达到工具调用上限", List.of(), List.copyOf(steps));
            }
            int index = steps.size() + 1;
            if (!seenCalls.add(new CallSignature(call.name(), call.arguments()))) {
                steps.add(new AgentStep(index, call.name(), StepStatus.REJECTED, "重复工具调用"));
                return new AgentResponse(AgentStatus.TOOL_FAILED,
                        "重复工具调用", List.of(), List.copyOf(steps));
            }

            final ToolResult result;
            try {
                long timeout = Math.min(remaining(deadline),
                        TimeUnit.SECONDS.toNanos(properties.stepTimeoutSeconds()));
                result = citations.collect(within(timeout, () ->
                        registry.execute(call.name(), call.arguments(), context)));
            } catch (IllegalArgumentException e) {
                return failedTool(steps, call.name(), AgentStatus.TOOL_FAILED,
                        StepStatus.REJECTED, "工具名称、参数或引用不合法");
            } catch (LlmClientException e) {
                return failedTool(steps, call.name(), e.isTimeout() ? AgentStatus.TIMEOUT : AgentStatus.TOOL_FAILED,
                        e.isTimeout() ? StepStatus.TIMEOUT : StepStatus.FAILED,
                        e.isTimeout() ? "工具执行超时" : "知识工具调用失败");
            } catch (RuntimeException e) {
                return failedTool(steps, call.name(), AgentStatus.TOOL_FAILED,
                        StepStatus.FAILED, "工具执行失败");
            }
            steps.add(new AgentStep(index, call.name(), StepStatus.SUCCEEDED,
                    limitSummary(result.content())));
            if (System.nanoTime() >= deadline) {
                return timedOut(steps);
            }
            if (result.status() == ToolResult.ToolStatus.INSUFFICIENT_EVIDENCE) {
                return new AgentResponse(AgentStatus.INSUFFICIENT_EVIDENCE,
                        NO_EVIDENCE, List.of(), List.copyOf(steps));
            }
            exchanges.add(new ToolExchange(call, result));
        }
        throw new IllegalStateException("Agent 决策循环意外结束");
    }

    /** 校验终答中的全局编号，仅返回被引用的真实来源。 */
    private AgentResponse finish(String answer, CitationCollector citations, List<AgentStep> steps) {
        if (answer == null || answer.isBlank()) {
            return new AgentResponse(AgentStatus.MODEL_FAILED,
                    "模型未返回有效终答", List.of(), List.copyOf(steps));
        }
        ValidatedAnswer validated = citationValidator.validate(answer, citations.available());
        if (!validated.answer().equals(answer.strip())) {
            return new AgentResponse(AgentStatus.MODEL_FAILED,
                    "模型引用无效", List.of(), List.copyOf(steps));
        }
        if (!citations.available().isEmpty() && validated.citations().isEmpty()) {
            return new AgentResponse(AgentStatus.INSUFFICIENT_EVIDENCE,
                    NO_EVIDENCE, List.of(), List.copyOf(steps));
        }
        return new AgentResponse(AgentStatus.COMPLETED,
                validated.answer(), validated.citations(), List.copyOf(steps));
    }

    /** 生成不包含工具原始异常与长正文的失败轨迹。 */
    private AgentResponse failedTool(List<AgentStep> previous, String name, AgentStatus status, StepStatus stepStatus,
                                     String summary) {
        List<AgentStep> steps = new ArrayList<>(previous);
        steps.add(new AgentStep(steps.size() + 1, name, stepStatus, summary));
        return new AgentResponse(status, summary, List.of(), List.copyOf(steps));
    }

    /** 根据模型异常是否超时返回不同的停止状态。 */
    private AgentResponse modelFailure(LlmClientException e, List<AgentStep> steps) {
        return new AgentResponse(e.isTimeout() ? AgentStatus.TIMEOUT : AgentStatus.MODEL_FAILED,
                e.isTimeout() ? "模型请求超时" : "模型调用失败", List.of(), List.copyOf(steps));
    }

    /** 在总期限届满时返回明确状态与已完成的轨迹。 */
    private AgentResponse timedOut(List<AgentStep> steps) {
        return new AgentResponse(AgentStatus.TIMEOUT,
                "Agent 总执行时间超限", List.of(), List.copyOf(steps));
    }

    /** 返回距离总期限剩余的纳秒数，避免开始过期的调用。 */
    private long remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new LlmClientException("Agent 总执行时间超限", true);
        }
        return nanos;
    }

    /** 在有界线程池内执行阻塞操作，并在超时后发出中断。 */
    private <T> T within(long timeoutNanos, Callable<T> action) {
        final Future<T> future;
        try {
            future = workers.submit(action);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Agent 工作线程已满", e);
        }
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmClientException("Agent 阶段执行超时", true, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmClientException("Agent 执行被中断", true, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Agent 阶段执行失败", e.getCause());
        }
    }

    /** 应用退出时释放 Agent 工作线程。 */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    /** 限制公开轨迹的单条摘要长度。 */
    private String limitSummary(String content) {
        int limit = properties.maxStepSummaryChars();
        return content.length() <= limit ? content : content.substring(0, limit);
    }

    /** 标识一次模型建议，防止相同工具及参数重复执行。 */
    private record CallSignature(String name, tools.jackson.databind.JsonNode arguments) {
    }
}



