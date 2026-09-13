package com.junhaohan.knowledgeingestion.embedding.service;

import com.junhaohan.knowledgeingestion.config.EmbeddingProperties;
import com.junhaohan.knowledgeingestion.embedding.dto.EmbeddingRequest;
import com.junhaohan.knowledgeingestion.embedding.dto.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 通过 HTTP 调用 Python Embedding 服务。
 */
@Component
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding texts must not be empty");
        }

        EmbeddingRequest request = new EmbeddingRequest();
        request.setTexts(texts);
        String requestBody = toJson(request);

        EmbeddingResponse response = callEmbeddingService(requestBody);

        if (response == null || response.getVectors() == null) {
            throw new IllegalStateException("Empty embedding response");
        }

        if (!properties.getDimension().equals(response.getDimension())) {
            throw new IllegalStateException("Embedding dimension mismatch");
        }

        if (response.getVectors().size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch"
            );
        }

        return response.getVectors();
    }

    private String toJson(EmbeddingRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize embedding request", e);
        }
    }

    private EmbeddingResponse callEmbeddingService(String requestBody) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(properties.getBaseUrl() + "/embed"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Embedding service request failed, status="
                                + httpResponse.statusCode()
                                + ", body="
                                + httpResponse.body()
                );
            }

            return OBJECT_MAPPER.readValue(httpResponse.body(), EmbeddingResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding service request failed", e);
        }
    }
}
