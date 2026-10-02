package com.junhaohan.knowledgeingestion.workflow.controller;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.SaveRequest;
import com.junhaohan.knowledgeingestion.workflow.service.WorkflowDefinitionService;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.StartRequest;
import com.junhaohan.knowledgeingestion.workflow.service.WorkflowRunService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供流程定义的创建、追加版本及查询接口。 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowDefinitionService service;
    private final WorkflowRunService runs;

    /** 注入定义版本服务。 */
    public WorkflowController(WorkflowDefinitionService service, WorkflowRunService runs) {
        this.service = service;
        this.runs = runs;
    }

    /** 创建流程及版本 1，返回生成的 workflowId。 */
    @PostMapping
    public ResponseEntity<WorkflowDefinition> create(@RequestBody SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    /** 查询流程当前版本，或用 version 指定历史版本。 */
    @GetMapping("/{id}")
    public WorkflowDefinition get(@PathVariable String id,
                                  @RequestParam(required = false) Integer version) {
        return service.get(id, version);
    }

    /** 创建新版本并返回新的版本号和完整定义。 */
    @PostMapping("/{id}/versions")
    public ResponseEntity<WorkflowDefinition> createVersion(@PathVariable String id,
                                                            @RequestBody SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createVersion(id, request));
    }

    /** 使用指定或最新定义版本同步执行，并返回最终运行记录。 */
    @PostMapping("/{id}/runs")
    public WorkflowRun startRun(@PathVariable String id, @RequestBody StartRequest request) {
        return runs.start(id, request);
    }

    /** 查询运行记录，便于核对节点顺序与最终引用。 */
    @GetMapping("/runs/{runId}")
    public WorkflowRun getRun(@PathVariable String runId) {
        return runs.get(runId);
    }
}