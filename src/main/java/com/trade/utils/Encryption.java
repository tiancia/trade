package com.trade.utils;

import com.trade.constdef.RequestPath;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class Encryption {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public static SignResult sign(String method, String requestPath, String body) {
        try {
            String timestamp = java.time.Instant.now().toString();

            if (body == null) {
                body = "";
            }

            String preHash = timestamp + method.toUpperCase() + requestPath + body;

            String sign = hmacSha256Base64(preHash, RequestPath.getOkSecretKey());

            return new SignResult(timestamp, sign);
        } catch (Exception e) {
            throw new RuntimeException("OKX sign error", e);
        }
    }

    public static String hmacSha256Base64(String data, String secretKey) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);

        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256
        );

        mac.init(secretKeySpec);

        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        return Base64.getEncoder().encodeToString(hash);
    }

    public static class SignResult {
        private final String timestamp;
        private final String sign;

        public SignResult(String timestamp, String sign) {
            this.timestamp = timestamp;
            this.sign = sign;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getSign() {
            return sign;
        }
    }
}