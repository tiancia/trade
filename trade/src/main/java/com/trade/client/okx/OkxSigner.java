package com.trade.client.okx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

final class OkxSigner {
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final DateTimeFormatter REST_TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final OkxClientProperties properties;

    OkxSigner(OkxClientProperties properties) {
        this.properties = properties;
    }

    SignResult signRest(String method, String requestPath, String body) {
        String timestamp = REST_TIMESTAMP_FORMATTER.format(Instant.now());
        String payload = timestamp + method.toUpperCase() + requestPath + nullToEmpty(body);
        return new SignResult(timestamp, hmacSha256Base64(payload, properties.requiredSecretKey()));
    }

    String signWebSocket(String timestamp, String method, String requestPath) {
        return hmacSha256Base64(timestamp + method.toUpperCase() + requestPath, properties.requiredSecretKey());
    }

    static String hmacSha256Base64(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("OKX sign error", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record SignResult(String timestamp, String sign) {
    }
}
