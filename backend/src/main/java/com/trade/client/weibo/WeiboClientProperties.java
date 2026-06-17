package com.trade.client.weibo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.weibo")
public class WeiboClientProperties {
    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "";
    private String adminToken = "";
    private String oauthBaseUrl = WeiboEndpoints.DEFAULT_OAUTH_BASE_URL;
    private String apiBaseUrl = WeiboEndpoints.DEFAULT_API_BASE_URL;
    private ProxyProperties proxy = new ProxyProperties();

    public String requiredClientId() {
        return requiredText(clientId, "trade.weibo.client-id is required");
    }

    public String requiredClientSecret() {
        return requiredText(clientSecret, "trade.weibo.client-secret is required");
    }

    public String requiredRedirectUri() {
        return requiredText(redirectUri, "trade.weibo.redirect-uri is required");
    }

    public String requiredAdminToken() {
        return requiredText(adminToken, "trade.weibo.admin-token is required");
    }

    public String normalizedOauthBaseUrl() {
        return trimRightSlash(requiredText(oauthBaseUrl, "trade.weibo.oauth-base-url is required"));
    }

    public String normalizedApiBaseUrl() {
        return trimRightSlash(requiredText(apiBaseUrl, "trade.weibo.api-base-url is required"));
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String trimRightSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
