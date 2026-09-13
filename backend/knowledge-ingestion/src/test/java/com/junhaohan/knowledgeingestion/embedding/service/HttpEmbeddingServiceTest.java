package com.junhaohan.knowledgeingestion.embedding.service;

import com.junhaohan.knowledgeingestion.config.EmbeddingProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEmbeddingServiceTest {

    @Test
    void embedSendsJsonBodyToEmbeddingEndpoint() throws IOException {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embed", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = """
                    {"dimension":2,"vectors":[[0.1,0.2],[0.3,0.4]]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            EmbeddingProperties properties = new EmbeddingProperties();
            properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            properties.setDimension(2);

            HttpEmbeddingService service = new HttpEmbeddingService(properties);

            List<List<Float>> vectors = service.embed(List.of("hello", "world"));

            assertThat(vectors).hasSize(2);
            assertThat(requestPath.get()).isEqualTo("/embed");
            assertThat(contentType.get()).startsWith("application/json");
            assertThat(requestBody.get()).isEqualTo("{\"texts\":[\"hello\",\"world\"]}");
        } finally {
            server.stop(0);
        }
    }
}
