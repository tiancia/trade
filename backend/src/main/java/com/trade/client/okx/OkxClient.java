package com.trade.client.okx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.PlaceOrderReq;

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
        this(properties, buildHttpClient(properties));
    }

    OkxClient(OkxClientProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
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
            return send(buildGetRequest(path, req, needAuth), null);
        } catch (Exception e) {
            throw new RuntimeException("OKX GET request error", e);
        }
    }

    public String postRaw(String path, Object req, boolean needAuth) {
        try {
            String clientOrderId = OkxEndpoints.TRADE_ORDER.equals(path) && req instanceof PlaceOrderReq order
                    ? order.getClOrdId() : null;
            return send(buildPostRequest(path, req, needAuth), clientOrderId);
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

    private String send(HttpRequest request, String clientOrderId) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                String rejection = explicitOrderRejection(statusCode, response.body(), clientOrderId);
                if (rejection != null) {
                    // Only the verified code enters the ordinary business-rejection path.
                    return objectMapper.createObjectNode().put("code", rejection)
                            .put("msg", "Request rejected (HTTP " + statusCode + ")").toString();
                }
                throw new RuntimeException("OKX HTTP error, status=" + statusCode);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Send OKX request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Send OKX request error", e);
        }
    }

    private String explicitOrderRejection(int status, String body, String clientOrderId) {
        if ((status != 400 && status != 401) || clientOrderId == null || clientOrderId.isBlank() || body == null) {
            return null;
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (java.io.IOException invalid) {
            return null;
        }
        if (envelope == null || !envelope.isObject()) return null;
        String code = envelope.path("code").textValue();
        if (code == null) return null;
        JsonNode rows = envelope.path("data");
        if (!rows.isMissingNode() && !rows.isNull()) {
            if (!rows.isArray() || rows.size() > 1) return null;
            if (rows.size() == 1) {
                JsonNode row = rows.get(0);
                String rowCode = row.path("sCode").textValue();
                if (rowCode == null || !clientOrderId.equals(row.path("clOrdId").textValue())
                        || (row.hasNonNull("ordId") && !"".equals(row.path("ordId").textValue()))
                        || !("1".equals(code) || rowCode.equals(code))) return null;
                code = rowCode;
            }
        }
        // OKX documents these status/code pairs as request validation or authentication failures.
        if (status == 401) return code.matches("501(0[1-9]|1[0-4])") ? code : null;
        return switch (code) {
            case "50014", "50015", "50016", "50100", "51000" -> code;
            default -> null;
        };
    }

    private <T> OkxResponse<T> parseOkxResponse(String body, Class<T> dataClass) {
        try {
            JavaType javaType = objectMapper.getTypeFactory()
                    .constructParametricType(OkxResponse.class, dataClass);
            return objectMapper.readValue(body, javaType);
        } catch (Exception e) {
            // Parser exception messages may contain private account or order response fragments.
            throw new RuntimeException("Parse OKX response error");
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
