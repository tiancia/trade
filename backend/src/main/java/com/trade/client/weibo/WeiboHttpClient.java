package com.trade.client.weibo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class WeiboHttpClient {
    private static final Logger log = LoggerFactory.getLogger(WeiboHttpClient.class);

    private final WeiboClientProperties properties;
    private final ObjectMapper objectMapper;
    private final Sender sender;

    public WeiboHttpClient(WeiboClientProperties properties) {
        this(properties, javaSender(buildHttpClient(properties)));
    }

    WeiboHttpClient(WeiboClientProperties properties, Sender sender) {
        this.properties = properties;
        this.sender = sender;
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        WeiboClientProperties.ProxyProperties proxy = properties.getProxy();
        log.info(
                "Weibo HTTP client initialized: oauthBaseUrl={}, apiBaseUrl={}, proxyEnabled={}, proxyHost={}, proxyPort={}",
                properties.normalizedOauthBaseUrl(),
                properties.normalizedApiBaseUrl(),
                proxy != null && proxy.isEnabled(),
                proxy == null ? null : proxy.getHost(),
                proxy == null ? null : proxy.getPort()
        );
    }

    public WeiboClientProperties properties() {
        return properties;
    }

    public JsonNode getJson(String baseUrl, String path, Map<String, ?> queryParams) {
        URI uri = URI.create(baseUrl + pathWithQuery(path, queryParams));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("Accept", "application/json")
                .build();
        return parseJson(send(request));
    }

    public JsonNode postFormJson(String baseUrl, String path, Map<String, ?> formParams) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(formParams)))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();
        return parseJson(send(request));
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Parse Weibo response error, body=" + raw, e);
        }
    }

    private String send(HttpRequest request) {
        long startedAt = System.currentTimeMillis();
        String safeUri = redactedUri(request.uri());
        log.info("Send Weibo HTTP request: method={}, uri={}", request.method(), safeUri);
        HttpResponse<String> response;
        try {
            response = sender.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Send Weibo HTTP request interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Send Weibo HTTP request error", e);
        }

        int statusCode = response.statusCode();
        log.info(
                "Weibo HTTP response received: method={}, uri={}, status={}, elapsedMs={}, bodyChars={}",
                request.method(),
                safeUri,
                statusCode,
                System.currentTimeMillis() - startedAt,
                response.body() == null ? 0 : response.body().length()
        );
        if (statusCode < 200 || statusCode >= 300) {
            throw new WeiboHttpException(request.method(), safeUri, statusCode, response.body());
        }
        return response.body();
    }

    static String pathWithQuery(String path, Map<String, ?> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        return path + "?" + formEncode(queryParams);
    }

    static String formEncode(Map<String, ?> params) {
        Map<String, ?> safeParams = params == null ? Map.of() : new LinkedHashMap<>(params);
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, ?> entry : safeParams.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            joiner.add(encode(entry.getKey()) + "=" + encode(String.valueOf(value)));
        }
        return joiner.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String redactedUri(URI uri) {
        return uri.toString().replaceAll("(?i)(access_token|client_secret)=[^&]*", "$1=***");
    }

    static HttpClient buildHttpClient(WeiboClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        WeiboClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }

    private static Sender javaSender(HttpClient client) {
        return request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
