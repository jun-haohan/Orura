package com.junhaohan.knowledgeingestion.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

@Configuration
@RequiredArgsConstructor
public class MilvusConfig {

    private final MilvusProperties properties;

    @Bean
    public MilvusClientV2 milvusClient() {
        ConnectConfig config = ConnectConfig.builder()
                .uri(properties.getUri())
                .build();

        return new MilvusClientV2(config);
    }
}