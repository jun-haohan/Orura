package com.junhaohan.knowledgeingestion.rag.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 通过 OpenAI 兼容的非流式接口调用问答模型。 */
@Component
public class HttpLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration requestTimeout;
    private final int maxOutputTokens;
    private final double temperature;

    /** 从配置初始化模型端点、生成参数和 HTTP 客户端。 */
    public HttpLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.model}") String model,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${llm.request-timeout-seconds:120}") int requestTimeoutSeconds,
            @Value("${llm.max-output-tokens:512}") int maxOutputTokens,
            @Value("${llm.temperature:0.7}") double temperature) {
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
        this.apiKey = apiKey;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    /** 向模型发送系统规则与用户问题，并提取回答正文。 */
    @Override
    public String generate(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()
                || userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("模型提示词不能为空");
        }

        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", maxOutputTokens,
                "temperature", temperature
        );

        final String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new LlmClientException("模型请求序列化失败", false, e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmClientException("模型服务返回 HTTP " + response.statusCode(), false);
            }
            return extractAnswer(response.body());
        } catch (HttpTimeoutException e) {
            throw new LlmClientException("模型服务请求超时", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("模型服务请求被中断", false, e);
        } catch (IOException e) {
            throw new LlmClientException("模型服务连接失败", false, e);
        }
    }

    /** 从 OpenAI 兼容响应中提取首条非空回答。 */
    private String extractAnswer(String responseBody) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new LlmClientException("模型响应不是合法 JSON", false, e);
        }

        JsonNode choices = root.get("choices");
        JsonNode choice = choices == null ? null : choices.get(0);
        JsonNode message = choice == null ? null : choice.get("message");
        JsonNode content = message == null ? null : message.get("content");
        if (content == null || !content.isTextual() || content.asString().isBlank()) {
            throw new LlmClientException("模型响应缺少回答正文", false);
        }
        return content.asString().trim();
    }
}