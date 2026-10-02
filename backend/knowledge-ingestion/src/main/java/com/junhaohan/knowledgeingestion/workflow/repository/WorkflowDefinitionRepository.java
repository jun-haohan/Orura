package com.junhaohan.knowledgeingestion.workflow.repository;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowDefinition;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 读取流程定义版本，并通过 MongoDB insert 保证新版本不会覆盖旧版本。 */
public interface WorkflowDefinitionRepository extends MongoRepository<WorkflowDefinition, String> {

    /** 按版本降序读取指定流程的最新定义。 */
    Optional<WorkflowDefinition> findFirstByWorkflowIdOrderByVersionDesc(String workflowId);
}