package com.trade.client.polymarket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

public class PolymarketHttpClient {
    private static final Logger log = LoggerFactory.getLogger(PolymarketHttpClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PolymarketClientProperties properties;

    public PolymarketHttpClient(PolymarketClientProperties properties) {
        this.properties = properties;
        this.httpClient = buildHttpClient(properties);
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        PolymarketClientProperties.ProxyProperties proxy = properties.getProxy();
        log.info(
                "Polymarket HTTP client initialized: gammaBaseUrl={}, clobBaseUrl={}, proxyEnabled={}, proxyHost={}, proxyPort={}",
                properties.normalizedGammaBaseUrl(),
                properties.normalizedClobBaseUrl(),
                proxy != null && proxy.isEnabled(),
                proxy == null ? null : proxy.getHost(),
                proxy == null ? null : proxy.getPort()
        );
    }

    public <T> T getGamma(String path, Map<String, ?> queryParams, JavaType responseType) {
        return get(properties.normalizedGammaBaseUrl(), path, queryParams, responseType);
    }

    public <T> T getClob(String path, Map<String, ?> queryParams, JavaType responseType) {
        return get(properties.normalizedClobBaseUrl(), path, queryParams, responseType);
    }

    public String getRawUrl(String url) {
        HttpRequest request = baseGetBuilder(URI.create(url))
                .build();
        return send(request);
    }

    private <T> T get(String baseUrl, String path, Map<String, ?> queryParams, JavaType responseType) {
        String pathWithQuery = PolymarketQueryStringBuilder.build(path, queryParams);
        HttpRequest request = baseGetBuilder(URI.create(baseUrl + pathWithQuery))
                .build();
        String raw = send(request);
        try {
            return objectMapper.readValue(raw, responseType);
        } catch (Exception e) {
            throw new RuntimeException("Parse Polymarket response error, body=" + raw, e);
        }
    }

    private static HttpRequest.Builder baseGetBuilder(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        long startedAt = System.currentTimeMillis();
        log.info("Send Polymarket HTTP request: method={}, uri={}", request.method(), request.uri());
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Send Polymarket HTTP request interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Send Polymarket HTTP request error", e);
        }

        int statusCode = response.statusCode();
        log.info(
                "Polymarket HTTP response received: method={}, uri={}, status={}, elapsedMs={}, bodyChars={}",
                request.method(),
                request.uri(),
                statusCode,
                System.currentTimeMillis() - startedAt,
                response.body() == null ? 0 : response.body().length()
        );
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("Polymarket HTTP error, status=" + statusCode + ", body=" + response.body());
        }
        return response.body();
    }

    static HttpClient buildHttpClient(PolymarketClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        PolymarketClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
