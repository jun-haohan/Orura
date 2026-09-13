package com.junhaohan.knowledgeingestion.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 开启 Spring 异步任务能力。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}