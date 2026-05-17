package com.trade.client.okx;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.okx")
public class OkxClientProperties {
    private String baseUrl = OkxEndpoints.DEFAULT_BASE_URL;
    private String wsPublicUrl = OkxEndpoints.DEFAULT_WS_PUBLIC_URL;
    private String wsPrivateUrl = OkxEndpoints.DEFAULT_WS_PRIVATE_URL;
    private String accessKey;
    private String secretKey;
    private String passphrase;
    private ProxyProperties proxy = new ProxyProperties();

    String normalizedBaseUrl() {
        return trimRightSlash(requiredText(baseUrl, "trade.okx.base-url is required"));
    }

    String requiredAccessKey() {
        return requiredText(resolve(accessKey, "OKX_ACCESS_KEY", "OKX-ACCESS-KEY", "ak"), "OKX access key is required");
    }

    String requiredSecretKey() {
        return requiredText(resolve(secretKey, "OKX_SECRET_KEY", "OKX-SECRET-KEY", "sk"), "OKX secret key is required");
    }

    String requiredPassphrase() {
        return requiredText(resolve(passphrase, "OKX_ACCESS_PASSPHRASE", "OKX-ACCESS-PASSPHRASE", "ap"), "OKX passphrase is required");
    }

    private static String resolve(String configured, String... envNames) {
        if (hasText(configured)) {
            return configured;
        }
        for (String envName : envNames) {
            String value = System.getenv(envName);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String requiredText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
