package com.trade.client.x;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

@Data
@ConfigurationProperties(prefix = "trade.x.client")
public class XClientProperties {
    private boolean enabled = false;
    @ToString.Exclude
    private String apiKey = "";
    @ToString.Exclude
    private String apiSecret = "";
    @ToString.Exclude
    private String accessToken = "";
    @ToString.Exclude
    private String accessTokenSecret = "";
    private String baseUrl = "https://api.x.com";
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 30;
    private ProxyProperties proxy = new ProxyProperties();

    public String requiredApiKey() {
        return requiredCredential(apiKey, "trade.x.client.api-key is required");
    }

    public String requiredApiSecret() {
        return requiredCredential(apiSecret, "trade.x.client.api-secret is required");
    }

    public String requiredAccessToken() {
        return requiredCredential(accessToken, "trade.x.client.access-token is required");
    }

    public String requiredAccessTokenSecret() {
        return requiredCredential(accessTokenSecret, "trade.x.client.access-token-secret is required");
    }

    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("trade.x.client.base-url is required");
        }
        String value = baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("trade.x.client.base-url must be an HTTP(S) URL without credentials, query or fragment");
            }
            return value;
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException("trade.x.client.base-url is invalid");
        }
    }

    private static String requiredCredential(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7897;
    }
}
