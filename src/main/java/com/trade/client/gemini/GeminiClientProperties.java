package com.trade.client.gemini;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.gemini")
public class GeminiClientProperties {
    private String apiKey;
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private String model = "gemini-3-flash-preview";
    private ProxyProperties proxy = new ProxyProperties();

    String requiredApiKey() {
        String resolved = hasText(apiKey) ? apiKey : System.getenv("GEMINI_API_KEY");
        if (!hasText(resolved)) {
            throw new IllegalArgumentException("Gemini API key is required");
        }
        return resolved;
    }

    String normalizedBaseUrl() {
        String value = requireText(baseUrl, "trade.gemini.base-url is required");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    String requiredModel() {
        return requireText(model, "trade.gemini.model is required");
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
