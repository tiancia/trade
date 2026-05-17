package com.trade.client.okx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.OkxResponse;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OkxClient implements OkxRestClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OkxClientProperties properties;
    private final OkxSigner signer;

    public OkxClient() {
        this(new OkxClientProperties());
    }

    public OkxClient(OkxClientProperties properties) {
        this.properties = properties;
        this.httpClient = buildHttpClient(properties);
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.signer = new OkxSigner(properties);
    }

    @Override
    public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
        String body = getRaw(path, req, needAuth);
        return parseOkxResponse(body, dataClass);
    }

    @Override
    public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
        String body = postRaw(path, req, needAuth);
        return parseOkxResponse(body, dataClass);
    }

    public String getRaw(String path, Object req, boolean needAuth) {
        try {
            return send(buildGetRequest(path, req, needAuth));
        } catch (Exception e) {
            throw new RuntimeException("OKX GET request error", e);
        }
    }

    public String postRaw(String path, Object req, boolean needAuth) {
        try {
            return send(buildPostRequest(path, req, needAuth));
        } catch (Exception e) {
            throw new RuntimeException("OKX POST request error", e);
        }
    }

    private HttpRequest buildGetRequest(String path, Object req, boolean needAuth) {
        String pathWithQuery = QueryStringBuilder.build(path, req);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.normalizedBaseUrl() + pathWithQuery))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (needAuth) {
            addAuthHeaders(builder, "GET", pathWithQuery, "");
        }

        return builder.build();
    }

    private HttpRequest buildPostRequest(String path, Object req, boolean needAuth) {
        try {
            String body = req == null ? "" : objectMapper.writeValueAsString(req);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.normalizedBaseUrl() + path))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

            if (needAuth) {
                addAuthHeaders(builder, "POST", path, body);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Build OKX POST request error", e);
        }
    }

    private void addAuthHeaders(HttpRequest.Builder builder, String method, String requestPath, String body) {
        OkxSigner.SignResult signature = signer.signRest(method, requestPath, body);
        builder.header("OK-ACCESS-KEY", properties.requiredAccessKey());
        builder.header("OK-ACCESS-SIGN", signature.sign());
        builder.header("OK-ACCESS-TIMESTAMP", signature.timestamp());
        builder.header("OK-ACCESS-PASSPHRASE", properties.requiredPassphrase());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("OKX HTTP error, status=" + statusCode + ", body=" + response.body());
            }
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Send OKX request error", e);
        }
    }

    private <T> OkxResponse<T> parseOkxResponse(String body, Class<T> dataClass) {
        try {
            JavaType javaType = objectMapper.getTypeFactory()
                    .constructParametricType(OkxResponse.class, dataClass);
            return objectMapper.readValue(body, javaType);
        } catch (Exception e) {
            throw new RuntimeException("Parse OKX response error, body=" + body, e);
        }
    }

    static HttpClient buildHttpClient(OkxClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        OkxClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
