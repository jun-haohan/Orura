package com.junhaohan.knowledgeingestion.workflow.service;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.StartRequest;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.NodeRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.NodeStatus;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.Status;
import com.junhaohan.knowledgeingestion.workflow.repository.WorkflowRunRepository;
import com.junhaohan.knowledgeingestion.workflow.runtime.WorkflowExecutor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 固定流程定义版本，保存 RUNNING 记录并同步执行一次流程。 */
@Service
public class WorkflowRunService {

    private final WorkflowDefinitionService definitions;
    private final WorkflowRunRepository runs;
    private final WorkflowExecutor executor;

    /** 注入定义读取、运行记录仓库和节点执行器。 */
    public WorkflowRunService(WorkflowDefinitionService definitions, WorkflowRunRepository runs,
                              WorkflowExecutor executor) {
        this.definitions = definitions;
        this.runs = runs;
        this.executor = executor;
    }

    /** 创建运行记录，执行指定或当前版本并保存最后状态。 */
    public WorkflowRun start(String workflowId, StartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Workflow 运行请求不能为空");
        }
        WorkflowDefinition definition = definitions.get(workflowId, request.version());
        Map<String, Object> input = request.input() == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(request.input()));
        WorkflowRun running = new WorkflowRun(UUID.randomUUID().toString(), definition.workflowId(),
                definition.version(), Status.RUNNING, input, List.of(), null, List.of(),
                Instant.now(), null, null, null);
        runs.insert(running);
        AtomicReference<WorkflowRun> last = new AtomicReference<>(running);
        WorkflowRun finished;
        try {
            finished = executor.execute(definition, running, progress -> {
                last.set(progress);
                runs.save(progress);
            });
        } catch (RuntimeException error) {
            return runs.save(interrupted(last.get(), "EXECUTION_ERROR", "Workflow 执行意外中止"));
        }
        return runs.save(finished);
    }

    /** 单实例启动后终止上次进程遗留的运行记录，不自动重放节点。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (WorkflowRun run : runs.findByStatus(Status.RUNNING)) {
            runs.save(interrupted(run, "PROCESS_INTERRUPTED", "Workflow 进程中断"));
        }
    }

    /** 把执行中的最后一个节点及整个运行标记为失败。 */
    private WorkflowRun interrupted(WorkflowRun run, String code, String message) {
        List<NodeRun> nodes = new ArrayList<>(run.nodeRuns());
        if (!nodes.isEmpty()) {
            int last = nodes.size() - 1;
            NodeRun active = nodes.get(last);
            if (active.status() == NodeStatus.RUNNING) {
                nodes.set(last, new NodeRun(active.nodeId(), NodeStatus.FAILED,
                        active.startedAt(), Instant.now(), Map.of(), code, message));
            }
        }
        return new WorkflowRun(run.id(), run.workflowId(), run.version(), Status.FAILED,
                run.input(), List.copyOf(nodes), null, List.of(), run.startedAt(),
                Instant.now(), code, message);
    }

    /** 读取一次运行的最终状态及实际执行的节点。 */
    public WorkflowRun get(String runId) {
        return runs.findById(runId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow 运行记录不存在"));
    }
}
