package com.junhaohan.knowledgeingestion.controller;

import com.junhaohan.knowledgeingestion.service.SystemCleanupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统维护接口。
 */
@RestController
@RequestMapping("/api/maintenance")
public class SystemCleanupController {

    private final SystemCleanupService systemCleanupService;

    /** 初始化系统维护控制器。 */
    public SystemCleanupController(SystemCleanupService systemCleanupService) {
        this.systemCleanupService = systemCleanupService;
    }

    /**
     * 清空全部业务数据，但保留数据库和索引结构。
     */
    @PostMapping("/clear-all")
    public Map<String, Object> clearAll() {
        systemCleanupService.clearAll();

        return Map.of(
                "success", true,
                "message", "MongoDB、Milvus、Elasticsearch数据已清空"
        );
    }
}