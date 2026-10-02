package com.junhaohan.knowledgeingestion.workflow.repository;

import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun;
import com.junhaohan.knowledgeingestion.workflow.model.WorkflowRun.Status;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 保存和查询绑定了定义版本的 Workflow 运行记录。 */
public interface WorkflowRunRepository extends MongoRepository<WorkflowRun, String> {
    /** 查询单实例重启后仍标记为执行中的运行记录。 */
    List<WorkflowRun> findByStatus(Status status);
}
