package com.trade.client.okx;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.trade.common.CommonResponse;
import com.trade.constdef.RequestPath;
import com.trade.utils.Encryption;
import com.trade.utils.UrlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public class OkxClient {

    private static final Logger log = LoggerFactory.getLogger(OkxClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Builds the shared HTTP transport and JSON mapper used by all REST requests.
     */
    public OkxClient() {
        // TODO 代理
        this.httpClient =  HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)))
                .build();
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Sends a GET request to OKX and parses the standard OKX response wrapper.
     */
    public <T> CommonResponse<T> get(
            String path,
            Object req,
            boolean needAuth,
            Class<T> dataClass
    ) {
        String body = getRaw(path, req, needAuth);
        return parseOkxResponse(body, dataClass);
    }

    /**
     * Sends a POST request to OKX and parses the standard OKX response wrapper.
     */
    public <T> CommonResponse<T> post(
            String path,
            Object req,
            boolean needAuth,
            Class<T> dataClass
    ) {
        String body = postRaw(path, req, needAuth);
        return parseOkxResponse(body, dataClass);
    }

    /**
     * Sends a GET request and returns the raw response body. Useful for debugging new endpoints.
     */
    public String getRaw(String path, Object req, boolean needAuth) {
        try {
            HttpRequest request = buildGetRequest(path, req, needAuth);
            return send(request);
        } catch (Exception e) {
            throw new RuntimeException("OKX GET request error", e);
        }
    }

    /**
     * Sends a POST request and returns the raw response body. Useful for debugging new endpoints.
     */
    public String postRaw(String path, Object req, boolean needAuth) {
        try {
            HttpRequest request = buildPostRequest(path, req, needAuth);
            return send(request);
        } catch (Exception e) {
            throw new RuntimeException("OKX POST request error", e);
        }
    }

    /**
     * Builds a GET request and signs the path including query parameters when auth is required.
     */
    private HttpRequest buildGetRequest(String path, Object req, boolean needAuth) {
        String pathWithQuery = UrlBuilder.buildUrl(path, req);
        String url = RequestPath.DOMAIN + pathWithQuery;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (needAuth) {
            addAuthHeaders(builder, "GET", pathWithQuery, "");
        }

        return builder.build();
    }

    /**
     * Builds a POST request and signs the exact JSON body when auth is required.
     */
    private HttpRequest buildPostRequest(String path, Object req, boolean needAuth) {
        try {
            String body = req == null ? "" : objectMapper.writeValueAsString(req);
            String url = RequestPath.DOMAIN + path;

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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

    /**
     * Adds OKX v5 authentication headers using timestamp + method + requestPath + body.
     */
    private void addAuthHeaders(
            HttpRequest.Builder builder,
            String method,
            String requestPath,
            String body
    ) {
        String timestamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
        log.info("timestamp = {}",timestamp);
        String preHash = timestamp
                + method.toUpperCase()
                + requestPath
                + (body == null ? "" : body);


        String sign;
        try {
            sign = Encryption.hmacSha256Base64(
                    preHash,
                    RequestPath.getOkSecretKey()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        builder.header("OK-ACCESS-KEY", RequestPath.getOkAccessKey());
        builder.header("OK-ACCESS-SIGN", sign);
        builder.header("OK-ACCESS-TIMESTAMP", timestamp);
        builder.header("OK-ACCESS-PASSPHRASE", RequestPath.getOkAccessPassphrase());
    }

    /**
     * Executes an HTTP request and treats non-2xx responses as transport errors.
     */
    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException(
                        "OKX HTTP error, status=" + statusCode + ", body=" + body
                );
            }

            return body;
        } catch (Exception e) {
            throw new RuntimeException("Send OKX request error", e);
        }
    }

    /**
     * Parses OKX's common response shape and throws when OKX returns a non-zero business code.
     */
    private <T> CommonResponse<T> parseOkxResponse(String body, Class<T> dataClass) {
        try {
            JavaType javaType = objectMapper.getTypeFactory()
                    .constructParametricType(CommonResponse.class, dataClass);

            CommonResponse<T> resp = objectMapper.readValue(body, javaType);

            if (!"0".equals(resp.getCode())) {
                throw new RuntimeException(
                        "OKX business error, code=" + resp.getCode()
                                + ", msg=" + resp.getMsg()
                                + ", body=" + body
                );
            }

            return resp;
        } catch (Exception e) {
            throw new RuntimeException("Parse OKX response error, body=" + body, e);
        }
    }
}
