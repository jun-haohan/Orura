package com.junhaohan.knowledgeingestion.enums;

/**
 * 向量化状态
 */
public enum EmbeddingStatus {
    NOT_READY, // 解析失败，暂时不进行向量化
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}
