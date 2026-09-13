package com.junhaohan.knowledgeingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {
    private String baseUrl;
    private String model;
    private Integer dimension;
    private Integer batchSize;
}