package com.junhaohan.knowledgeingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String uri;
    private String collectionName;
}