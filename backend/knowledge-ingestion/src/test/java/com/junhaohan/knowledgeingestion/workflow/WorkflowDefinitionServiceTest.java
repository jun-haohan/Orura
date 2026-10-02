package com.junhaohan.knowledgeingestion.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition.SaveRequest;
import com.junhaohan.knowledgeingestion.workflow.repository.WorkflowDefinitionRepository;
import com.junhaohan.knowledgeingestion.workflow.service.WorkflowDefinitionService;
import com.junhaohan.knowledgeingestion.workflow.validation.WorkflowGraphValidator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

/** 验证创建、版本隔离、并发冲突重试及不存在版本的响应。 */
class WorkflowDefinitionServiceTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowGraphValidator validator = mock(WorkflowGraphValidator.class);
    private final WorkflowDefinitionService service = new WorkflowDefinitionService(repository, validator);

    /** 首次创建版本 1，并使用只允许插入的 Repository 方法。 */
    @Test
    void createsFirstImmutableVersion() {
        when(repository.insert(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SaveRequest request = request("first");

        WorkflowDefinition created = service.create(request);

        assertEquals(1, created.version());
        assertEquals(created.workflowId() + ":1", created.id());
        assertEquals("first", created.name());
        verify(validator).validate(request);
        verify(repository, never()).save(any(WorkflowDefinition.class));
    }

    /** 追加版本时保留旧定义，按当前版本及历史版本分别读取。 */
    @Test
    void createsAndReadsSeparateVersions() {
        WorkflowDefinition first = existing("wf", 1);
        AtomicReference<WorkflowDefinition> latest = new AtomicReference<>(first);
        when(repository.findFirstByWorkflowIdOrderByVersionDesc("wf"))
                .thenAnswer(invocation -> Optional.of(latest.get()));
        when(repository.insert(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinition inserted = invocation.getArgument(0);
                    latest.set(inserted);
                    return inserted;
                });
        when(repository.findById("wf:1")).thenReturn(Optional.of(first));

        WorkflowDefinition second = service.createVersion("wf", request("second"));

        assertEquals("wf:2", second.id());
        assertEquals("second", second.name());
        assertEquals(first, service.get("wf", 1));
        assertEquals(second, service.get("wf", null));
        verify(repository, never()).save(any(WorkflowDefinition.class));
    }

    /** 同时争用版本 2 时重新读取最新版本，再插入版本 3。 */
    @Test
    void retriesConflictingVersionWithoutOverwriting() {
        when(repository.findFirstByWorkflowIdOrderByVersionDesc("wf"))
                .thenReturn(Optional.of(existing("wf", 1)), Optional.of(existing("wf", 2)));
        when(repository.insert(any(WorkflowDefinition.class)))
                .thenThrow(new DuplicateKeyException("duplicate version"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinition created = service.createVersion("wf", request("new"));

        assertEquals(3, created.version());
        verify(repository, never()).save(any(WorkflowDefinition.class));
    }

    /** 不存在的流程或版本应明确返回 404，非法版本号返回参数错误。 */
    @Test
    void reportsMissingOrInvalidVersion() {
        when(repository.findFirstByWorkflowIdOrderByVersionDesc("wf"))
                .thenReturn(Optional.empty());
        when(repository.findById("wf:8")).thenReturn(Optional.empty());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.createVersion("wf", request("new")));
        assertEquals(404, missing.getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.get("wf", 8)).getStatusCode().value());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.get("wf", 0)).getMessage().contains("正整数"));
    }

    /** 构造供服务层测试的最小提交。 */
    private SaveRequest request(String name) {
        return new SaveRequest(name, null, List.of(), List.of());
    }

    /** 构造一个已经保存的指定版本定义。 */
    private WorkflowDefinition existing(String workflowId, int version) {
        return new WorkflowDefinition(workflowId + ":" + version, workflowId, version,
                "old", null, List.of(), List.of(), Instant.EPOCH);
    }
}