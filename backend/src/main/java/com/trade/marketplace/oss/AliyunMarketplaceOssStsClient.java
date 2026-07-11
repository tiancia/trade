package com.trade.marketplace.oss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.marketplace.exception.MarketplaceUnavailableException;
import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.model.MarketplaceApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AliyunMarketplaceOssStsClient implements MarketplaceOssStsClient {
    private static final DateTimeFormatter ALIYUN_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final MarketplaceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public AliyunMarketplaceOssStsClient(MarketplaceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newHttpClient());
    }

    AliyunMarketplaceOssStsClient(
            MarketplaceProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public MarketplaceApi.OssCredentials assumeRole(
            String roleArn,
            String roleSessionName,
            String policy,
            int durationSeconds
    ) {
        MarketplaceProperties.OssProperties oss = properties.getOss();
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", oss.requiredAccessKeyId());
        params.put("Action", "AssumeRole");
        params.put("DurationSeconds", String.valueOf(durationSeconds));
        params.put("Format", "JSON");
        params.put("Policy", policy);
        params.put("RoleArn", roleArn);
        params.put("RoleSessionName", roleSessionName);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", ALIYUN_TIMESTAMP.format(Instant.now()));
        params.put("Version", "2015-04-01");
        params.put("Signature", signature(params, oss.requiredAccessKeySecret()));

        HttpRequest request = HttpRequest.newBuilder(URI.create(oss.normalizedStsEndpoint() + "?" + query(params)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MarketplaceUnavailableException(
                        "Aliyun STS request failed with status "
                                + response.statusCode()
                                + ", body="
                                + response.body()
                );
            }
            return credentials(response.body());
        } catch (MarketplaceUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketplaceUnavailableException("Aliyun STS request was interrupted", e);
        } catch (Exception e) {
            throw new MarketplaceUnavailableException("Aliyun STS request failed", e);
        }
    }

    private MarketplaceApi.OssCredentials credentials(String body) {
        try {
            JsonNode node = objectMapper.readTree(body).path("Credentials");
            String accessKeyId = node.path("AccessKeyId").asText("");
            String accessKeySecret = node.path("AccessKeySecret").asText("");
            String securityToken = node.path("SecurityToken").asText("");
            String expiration = node.path("Expiration").asText("");
            if (accessKeyId.isBlank() || accessKeySecret.isBlank() || securityToken.isBlank() || expiration.isBlank()) {
                throw new MarketplaceUnavailableException("Aliyun STS response did not include credentials");
            }
            return new MarketplaceApi.OssCredentials(
                    accessKeyId,
                    accessKeySecret,
                    securityToken,
                    Instant.parse(expiration)
            );
        } catch (MarketplaceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceUnavailableException("failed to parse Aliyun STS response", e);
        }
    }

    private static String signature(Map<String, String> params, String accessKeySecret) {
        String stringToSign = "GET&%2F&" + percentEncode(query(params));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new MarketplaceUnavailableException("failed to sign Aliyun STS request", e);
        }
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
