package com.trade.client.polymarket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.polymarket.client")
public class PolymarketClientProperties {
    private String gammaBaseUrl = PolymarketEndpoints.DEFAULT_GAMMA_BASE_URL;
    private String clobBaseUrl = PolymarketEndpoints.DEFAULT_CLOB_BASE_URL;
    private ProxyProperties proxy = new ProxyProperties();

    public String normalizedGammaBaseUrl() {
        return trimRightSlash(requiredText(gammaBaseUrl, "trade.polymarket.client.gamma-base-url is required"));
    }

    public String normalizedClobBaseUrl() {
        return trimRightSlash(requiredText(clobBaseUrl, "trade.polymarket.client.clob-base-url is required"));
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
