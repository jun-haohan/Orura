package com.junhaohan.knowledgeingestion.client;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class ParserServiceClient {
    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${parser.service.url}")
    private String serviceUrl;

    public String parse(Path path) throws Exception {
        String boundary = "----JavaBoundary" + UUID.randomUUID();

        byte[] fileBytes = Files.readAllBytes(path);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + path.getFileName() + "\"\r\n").getBytes());
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
        body.write(fileBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/parse"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Parser service request failed: " + response.body());
        }

        return new ObjectMapper().readTree(response.body()).get("content").asString();
    }
}
