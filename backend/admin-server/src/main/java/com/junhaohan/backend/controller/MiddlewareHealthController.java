package com.junhaohan.backend.controller;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class MiddlewareHealthController {
    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;


    @GetMapping("/mysql")
    public String mysql() {
        jdbcTemplate.queryForObject("select 1", Integer.class);
        return "mysql ok\n";
    }

    @GetMapping("/mongo")
    public String mongo() {
        mongoTemplate.getDb().runCommand(new Document("ping", 1));
        return "mongo ok\n";
    }

    @GetMapping("/redis")
    public String redis() {
        redisTemplate.opsForValue().set("health", "ok\n");
        return redisTemplate.opsForValue().get("health");
    }

    @GetMapping("/es")
    public String es() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:9200"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        return response.statusCode() == 200 ? "es ok" : "es error";
    }
}
