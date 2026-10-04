package com.trade.client.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class TelegramHttpClient {
    private final TelegramClientProperties properties;
    private final Sender sender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TelegramHttpClient(TelegramClientProperties properties) {
        this(properties, defaultSender(properties));
    }

    TelegramHttpClient(TelegramClientProperties properties, Sender sender) {
        this.properties = Objects.requireNonNull(properties, "Telegram client properties are required");
        this.sender = Objects.requireNonNull(sender, "Telegram sender is required");
    }

    public TelegramClientProperties properties() {
        return properties;
    }

    JsonNode post(String method, Map<String, ?> parameters, int pollingTimeoutSeconds) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Telegram client is disabled");
        }
        if (method == null || !method.matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("Telegram method is invalid");
        }
        if (properties.getRequestTimeoutSeconds() <= 0 || pollingTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Telegram request timeout must be positive and polling timeout nonnegative");
        }
        String token = properties.requiredBotToken();
        String baseUrl = properties.normalizedBaseUrl();
        String body;
        try {
            body = objectMapper.writeValueAsString(parameters);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot encode Telegram request");
        }
        long timeoutSeconds = "getUpdates".equals(method)
                ? Math.max(properties.getRequestTimeoutSeconds(), (long) pollingTimeoutSeconds + 10)
                : properties.getRequestTimeoutSeconds();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Telegram request configuration");
        }

        HttpResponse<String> response;
        try {
            response = sender.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram request was interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Telegram request failed during transport");
        }
        int statusCode = response.statusCode();
        boolean httpSuccess = statusCode >= 200 && statusCode < 300;
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body());
        } catch (IOException | IllegalArgumentException e) {
            if (!httpSuccess) {
                throw new TelegramApiException(statusCode, null, null, null, null);
            }
            throw new IllegalStateException("Cannot parse Telegram response");
        }
        if (!httpSuccess || (envelope != null && envelope.path("ok").isBoolean()
                && !envelope.path("ok").booleanValue())) {
            throw apiException(statusCode, envelope, token);
        }
        if (envelope == null || !envelope.isObject() || !envelope.path("ok").isBoolean()
                || !envelope.path("ok").booleanValue() || !envelope.hasNonNull("result")) {
            throw new IllegalStateException("Telegram response is missing a successful result");
        }
        return envelope.get("result");
    }

    <T> T readResult(JsonNode result, Class<T> resultType) {
        try {
            return objectMapper.treeToValue(result, resultType);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decode Telegram result");
        }
    }

    private static TelegramApiException apiException(int statusCode, JsonNode envelope, String token) {
        JsonNode errorCode = envelope == null ? null : envelope.get("error_code");
        JsonNode description = envelope == null ? null : envelope.get("description");
        JsonNode parameters = envelope == null ? null : envelope.get("parameters");
        JsonNode retryAfter = parameters == null ? null : parameters.get("retry_after");
        JsonNode migrateTo = parameters == null ? null : parameters.get("migrate_to_chat_id");
        return new TelegramApiException(
                statusCode,
                integerValue(errorCode),
                description != null && description.isTextual() ? description.textValue().replace(token, "***") : null,
                integerValue(retryAfter),
                migrateTo != null && migrateTo.isIntegralNumber() && migrateTo.canConvertToLong() ? migrateTo.longValue() : null
        );
    }

    private static Integer integerValue(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }

    static HttpClient buildHttpClient(TelegramClientProperties properties) {
        Objects.requireNonNull(properties, "Telegram client properties are required");
        if (properties.getConnectTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("trade.telegram.connect-timeout-seconds must be positive");
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        TelegramClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            if (proxy.getHost() == null || proxy.getHost().isBlank() || proxy.getPort() <= 0 || proxy.getPort() > 65535) {
                throw new IllegalArgumentException("Telegram proxy host and port are invalid");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost().trim(), proxy.getPort())));
        }
        return builder.build();
    }

    private static Sender defaultSender(TelegramClientProperties properties) {
        HttpClient httpClient = buildHttpClient(properties);
        return request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
