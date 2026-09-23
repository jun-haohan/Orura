package com.junhaohan.knowledgeingestion.service;

/**
 * 系统数据清理服务。
 */
public interface SystemCleanupService {

    /**
     * 清空业务数据，同时保留各存储的数据结构。
     */
    void clearAll();
}
