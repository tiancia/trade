package com.trade.client.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.ai.client")
public class AiClientProperties {
    private Provider provider = Provider.GEMINI;
    private String apiKey;
    private String apiKeyEnvName;
    private String baseUrl;
    private String model;
    private ProxyProperties proxy = new ProxyProperties();
    private String chatCompletionsPath;
    private boolean jsonResponseFormatEnabled = true;
    private Double temperature;
    private Integer maxOutputTokens;

    public String requiredApiKey() {
        if (hasText(apiKey)) {
            return apiKey.trim();
        }

        String envName = requireText(
                hasText(apiKeyEnvName) ? apiKeyEnvName : provider().defaultApiKeyEnvName,
                "trade.ai.client.api-key-env-name is required when trade.ai.client.api-key is not set"
        );
        String resolved = System.getenv(envName);
        if (!hasText(resolved)) {
            throw new IllegalArgumentException(
                    "AI client API key is required. Set trade.ai.client.api-key or environment variable " + envName
            );
        }
        return resolved.trim();
    }

    public String normalizedBaseUrl() {
        String value = requireText(
                hasText(baseUrl) ? baseUrl : provider().defaultBaseUrl,
                "trade.ai.client.base-url is required"
        );
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String requiredModel() {
        return requireText(
                hasText(model) ? model : provider().defaultModel,
                "trade.ai.client.model is required"
        );
    }

    public String normalizedChatCompletionsPath() {
        String value = requireText(
                hasText(chatCompletionsPath) ? chatCompletionsPath : provider().defaultChatCompletionsPath,
                "trade.ai.client.chat-completions-path is required"
        );
        return value.startsWith("/") ? value : "/" + value;
    }

    private Provider provider() {
        return provider == null ? Provider.GEMINI : provider;
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum Provider {
        GEMINI(
                "https://generativelanguage.googleapis.com",
                "gemini-3-flash-preview",
                "GEMINI_API_KEY",
                null
        ),
        OPENAI_COMPATIBLE(
                null,
                null,
                null,
                "/v1/chat/completions"
        ),
        DEEPSEEK(
                "https://api.deepseek.com",
                "deepseek-v4-flash",
                "DEEPSEEK_API_KEY",
                "/chat/completions"
        ),
        KIMI(
                "https://api.moonshot.ai/v1",
                "kimi-k2.6",
                "MOONSHOT_API_KEY",
                "/chat/completions"
        );

        private final String defaultBaseUrl;
        private final String defaultModel;
        private final String defaultApiKeyEnvName;
        private final String defaultChatCompletionsPath;

        Provider(
                String defaultBaseUrl,
                String defaultModel,
                String defaultApiKeyEnvName,
                String defaultChatCompletionsPath
        ) {
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
            this.defaultApiKeyEnvName = defaultApiKeyEnvName;
            this.defaultChatCompletionsPath = defaultChatCompletionsPath;
        }
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
