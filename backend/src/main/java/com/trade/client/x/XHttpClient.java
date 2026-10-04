package com.trade.client.x;

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
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class XHttpClient {
    private final XClientProperties properties;
    private final Sender sender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public XHttpClient(XClientProperties properties) {
        this(properties, defaultSender(properties));
    }

    XHttpClient(XClientProperties properties, Sender sender) {
        this.properties = Objects.requireNonNull(properties, "X client properties are required");
        this.sender = Objects.requireNonNull(sender, "X sender is required");
    }

    public XClientProperties properties() {
        return properties;
    }

    JsonNode getMe() {
        return request("GET", "/2/users/me", null);
    }

    JsonNode publishText(String text) {
        return request("POST", "/2/tweets", Map.of("text", text));
    }

    private JsonNode request(String method, String path, Map<String, ?> parameters) {
        if (!properties.isEnabled()) throw new IllegalStateException("X client is disabled");
        if (properties.getRequestTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("trade.x.client.request-timeout-seconds must be positive");
        }
        URI uri = URI.create(properties.normalizedBaseUrl() + path);
        String authorization = XOAuth1Signer.authorization(properties, method, uri,
                UUID.randomUUID().toString().replace("-", ""), Instant.now().getEpochSecond());
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Authorization", authorization)
                    .header("Accept", "application/json");
            if (parameters == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(parameters), StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalArgumentException ignored) {
            throw new IllegalStateException("Cannot construct X request");
        }
        HttpResponse<String> response;
        try {
            response = sender.send(builder.build());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("X request was interrupted");
        } catch (IOException ignored) {
            throw new IllegalStateException("X request failed during transport");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) throw new XApiException(status);
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body());
        } catch (IOException | IllegalArgumentException ignored) {
            throw new IllegalStateException("Cannot parse X response");
        }
        if (envelope != null && envelope.hasNonNull("errors")
                && (!envelope.get("errors").isArray() || !envelope.get("errors").isEmpty())) {
            throw new XApiException(status);
        }
        if (envelope == null || !envelope.isObject() || !envelope.path("data").isObject()) {
            throw new IllegalStateException("X response is missing successful data");
        }
        return envelope.get("data");
    }

    <T> T readData(JsonNode data, Class<T> type) {
        try {
            return objectMapper.treeToValue(data, type);
        } catch (IOException | IllegalArgumentException ignored) {
            throw new IllegalStateException("Cannot decode X response data");
        }
    }

    static HttpClient buildHttpClient(XClientProperties properties) {
        Objects.requireNonNull(properties, "X client properties are required");
        if (properties.getConnectTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("trade.x.client.connect-timeout-seconds must be positive");
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        XClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            if (proxy.getHost() == null || proxy.getHost().isBlank() || proxy.getPort() <= 0 || proxy.getPort() > 65535) {
                throw new IllegalArgumentException("X proxy host and port are invalid");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost().trim(), proxy.getPort())));
        }
        return builder.build();
    }

    private static Sender defaultSender(XClientProperties properties) {
        HttpClient http = buildHttpClient(properties);
        return request -> http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
