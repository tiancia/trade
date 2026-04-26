package com.trade.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiTextClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiClientProperties properties;

    public OpenAiCompatibleAiTextClient(AiClientProperties properties) {
        this.httpClient = buildHttpClient(properties);
        this.properties = properties;
    }

    @Override
    public String generateJson(String prompt) {
        String requestBody;
        try {
            requestBody = buildRequestBody(prompt);
        } catch (Exception e) {
            throw new RuntimeException("Build OpenAI-compatible request body error", e);
        }

        String rawResponse = postRaw(requestBody);
        log.info("OpenAI-compatible raw response: {}", rawResponse);

        try {
            return extractAssistantContent(rawResponse);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Parse OpenAI-compatible response error", e);
        }
    }

    String postRaw(String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl()))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.requiredApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Send OpenAI-compatible HTTP request error", e);
        } catch (IOException e) {
            throw new RuntimeException("Send OpenAI-compatible HTTP request error", e);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("OpenAI-compatible HTTP error response: status={}, body={}", statusCode, response.body());
            throw new RuntimeException(
                    "OpenAI-compatible HTTP error, status=" + statusCode + ", body=" + response.body()
            );
        }
        return response.body();
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
        String content = extractText(choice.path("message").path("content"));
        if (content == null || content.isBlank()) {
            content = extractText(choice.path("text"));
        }
        if (content == null || content.isBlank()) {
            throw new RuntimeException(
                    "OpenAI-compatible response has no content, finishReason="
                            + choice.path("finish_reason").asText(null)
                            + ", contentNodeType="
                            + nodeType(choice.path("message").path("content"))
            );
        }
        return content;
    }

    private static String extractText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : node) {
                String partText = extractContentPartText(part);
                if (partText != null) {
                    text.append(partText);
                }
            }
            return text.length() == 0 ? null : text.toString();
        }
        if (node.isObject()) {
            String text = extractText(node.get("text"));
            if (text != null) {
                return text;
            }
            return extractText(node.get("value"));
        }
        return node.asText(null);
    }

    private static String extractContentPartText(JsonNode part) {
        if (part == null || part.isMissingNode() || part.isNull()) {
            return null;
        }
        if (part.isTextual()) {
            return part.asText();
        }
        if (part.isObject()) {
            String text = extractText(part.get("text"));
            if (text != null) {
                return text;
            }
            return extractText(part.get("content"));
        }
        return null;
    }

    private static String nodeType(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "missing";
        }
        return node.getNodeType().name();
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
