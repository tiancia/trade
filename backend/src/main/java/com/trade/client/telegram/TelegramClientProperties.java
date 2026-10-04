package com.trade.client.telegram;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

@Data
@ConfigurationProperties(prefix = "trade.telegram")
public class TelegramClientProperties {
    private boolean enabled = false;
    @ToString.Exclude
    private String botToken = "";
    private String baseUrl = "https://api.telegram.org";
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 30;
    private ProxyProperties proxy = new ProxyProperties();

    public String requiredBotToken() {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("trade.telegram.bot-token is required");
        }
        String token = botToken.trim();
        if (!token.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException("trade.telegram.bot-token has an invalid format");
        }
        return token;
    }

    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("trade.telegram.base-url is required");
        }
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("trade.telegram.base-url must be an HTTP(S) URL without credentials, query or fragment");
            }
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("trade.telegram.base-url is invalid");
        }
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7897;
    }
}
