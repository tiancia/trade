package com.trade.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiClientProperties;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleAiTextClient implements AiTextClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiClientProperties properties;

    public OpenAiCompatibleAiTextClient(AiClientProperties properties) {
        this.httpClient = buildHttpClient(properties);
        this.properties = properties;
    }

    @Override
    public String generateJson(String prompt) {
        try {
            String rawResponse = postRaw(buildRequestBody(prompt));
            return extractAssistantContent(rawResponse);
        } catch (Exception e) {
            throw new RuntimeException("Send OpenAI-compatible request error", e);
        }
    }

    String postRaw(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl()))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + properties.requiredApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException(
                        "OpenAI-compatible HTTP error, status=" + statusCode + ", body=" + response.body()
                );
            }
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Send OpenAI-compatible HTTP request error", e);
        }
    }

    private String buildRequestBody(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.requiredModel());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", prompt
        )));
        if (properties.isJsonResponseFormatEnabled()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (properties.getTemperature() != null) {
            body.put("temperature", properties.getTemperature());
        }
        if (properties.getMaxOutputTokens() != null) {
            body.put("max_tokens", properties.getMaxOutputTokens());
        }
        return objectMapper.writeValueAsString(body);
    }

    private String endpointUrl() {
        return properties.normalizedBaseUrl() + properties.normalizedChatCompletionsPath();
    }

    private String extractAssistantContent(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("OpenAI-compatible response has no choices");
        }

        JsonNode choice = choices.get(0);
        String content = choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new RuntimeException(
                    "OpenAI-compatible response has no content, finishReason="
                            + choice.path("finish_reason").asText(null)
            );
        }
        return content;
    }

    static HttpClient buildHttpClient(AiClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        AiClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
