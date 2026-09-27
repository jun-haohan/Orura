package com.junhaohan.knowledgeingestion.agent.model;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import java.util.List;

/** 保存工具状态、供模型使用的结果和真实文档引用。 */
public record ToolResult(ToolStatus status, String content, List<RagCitation> citations) {

    /** 复制引用并检查工具结果的必要字段。 */
    public ToolResult {
        if (status == null || content == null || citations == null) {
            throw new IllegalArgumentException("工具结果不能包含空字段");
        }
        citations = List.copyOf(citations);
    }

    /** 区分正常结果与知识工具报告的证据不足。 */
    public enum ToolStatus {
        SUCCEEDED,
        INSUFFICIENT_EVIDENCE
    }
}