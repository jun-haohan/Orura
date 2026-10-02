package com.junhaohan.knowledgeingestion.workflow.service;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.SaveRequest;
import com.junhaohan.knowledgeingestion.workflow.repository.WorkflowDefinitionRepository;
import com.junhaohan.knowledgeingestion.workflow.validation.WorkflowGraphValidator;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 校验并创建不可覆盖的流程定义版本，提供按流程及版本查询。 */
@Service
public class WorkflowDefinitionService {

    private static final int MAX_VERSION_RETRIES = 5;

    private final WorkflowDefinitionRepository repository;
    private final WorkflowGraphValidator validator;

    /** 注入流程定义仓库和保存前图校验器。 */
    public WorkflowDefinitionService(WorkflowDefinitionRepository repository,
                                     WorkflowGraphValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /** 创建一个流程及其第一版定义。 */
    public WorkflowDefinition create(SaveRequest request) {
        validator.validate(request);
        String workflowId = UUID.randomUUID().toString();
        return repository.insert(definition(workflowId, 1, request));
    }

    /** 读取当前版本或指定版本；找不到时由接口返回 404。 */
    public WorkflowDefinition get(String workflowId, Integer version) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("Workflow ID 不能为空");
        }
        if (version != null && version < 1) {
            throw new IllegalArgumentException("Workflow 版本必须为正整数");
        }
        Optional<WorkflowDefinition> found = version == null
                ? repository.findFirstByWorkflowIdOrderByVersionDesc(workflowId)
                : repository.findById(workflowId + ":" + version)
                .filter(item -> workflowId.equals(item.workflowId()) && version == item.version());
        return found.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow 定义或版本不存在"));
    }

    /** 校验并追加新版本；并发争用相同版本号时重新读取最新版本。 */
    public WorkflowDefinition createVersion(String workflowId, SaveRequest request) {
        validator.validate(request);
        for (int attempt = 0; attempt < MAX_VERSION_RETRIES; attempt++) {
            WorkflowDefinition latest = get(workflowId, null);
            WorkflowDefinition next = definition(workflowId, Math.addExact(latest.version(), 1), request);
            try {
                // insert 拒绝重复 _id；不能改为 save，否则并发请求可能覆盖旧版本。
                return repository.insert(next);
            } catch (DuplicateKeyException conflict) {
                if (attempt == MAX_VERSION_RETRIES - 1) {
                    throw new IllegalStateException("Workflow 版本创建冲突，请重试", conflict);
                }
            }
        }
        throw new IllegalStateException("Workflow 版本创建失败");
    }

    /** 将一次提交封装为带独立文档 ID 的不可变版本。 */
    private WorkflowDefinition definition(String workflowId, int version, SaveRequest request) {
        return new WorkflowDefinition(workflowId + ":" + version, workflowId, version,
                request.name(), request.inputFields(), request.nodes(), request.edges(), Instant.now());
    }
}