package com.trade.client.x;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** OAuth 1.0a HMAC-SHA1; JSON payloads are not form parameters (RFC 5849 section 3.4.1.3.1). */
final class XOAuth1Signer {
    private XOAuth1Signer() {
    }

    static String authorization(XClientProperties properties, String method, URI uri, String nonce, long timestamp) {
        Map<String, String> oauth = new LinkedHashMap<>();
        oauth.put("oauth_consumer_key", properties.requiredApiKey());
        oauth.put("oauth_nonce", nonce);
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", Long.toString(timestamp));
        oauth.put("oauth_token", properties.requiredAccessToken());
        oauth.put("oauth_version", "1.0");
        oauth.put("oauth_signature", signature(method, uri, oauth,
                properties.requiredApiSecret(), properties.requiredAccessTokenSecret()));
        return "OAuth " + oauth.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=\"" + encode(entry.getValue()) + "\"")
                .collect(Collectors.joining(", "));
    }

    static String signature(String method, URI uri, Map<String, String> parameters,
                            String apiSecret, String accessTokenSecret) {
        List<Parameter> values = new ArrayList<>();
        parameters.forEach((key, value) -> {
            if (!"oauth_signature".equals(key) && !"realm".equals(key)) {
                values.add(new Parameter(encode(key), encode(value)));
            }
        });
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            try {
                for (String pair : query.split("&", -1)) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    if (!"oauth_signature".equals(key)) {
                        String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
                        values.add(new Parameter(encode(key), encode(value)));
                    }
                }
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("Invalid X signing URI");
            }
        }
        values.sort(Comparator.comparing(Parameter::name).thenComparing(Parameter::value));
        String normalized = values.stream().map(value -> value.name() + "=" + value.value())
                .collect(Collectors.joining("&"));
        String base = method.toUpperCase(Locale.ROOT) + "&" + encode(baseUri(uri)) + "&" + encode(normalized);
        String key = encode(apiSecret) + "&" + encode(accessTokenSecret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("Cannot sign X request");
        }
    }

    static String encode(String value) {
        StringBuilder result = new StringBuilder();
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int valueByte = raw & 0xff;
            if ((valueByte >= 'a' && valueByte <= 'z') || (valueByte >= 'A' && valueByte <= 'Z')
                    || (valueByte >= '0' && valueByte <= '9') || valueByte == '-' || valueByte == '.'
                    || valueByte == '_' || valueByte == '~') {
                result.append((char) valueByte);
            } else {
                result.append('%').append(hex[valueByte >>> 4]).append(hex[valueByte & 0xf]);
            }
        }
        return result.toString();
    }

    private static String baseUri(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        String path = uri.getRawPath();
        return scheme + "://" + host + (defaultPort ? "" : ":" + port)
                + (path == null || path.isEmpty() ? "/" : path);
    }

    private record Parameter(String name, String value) {
    }
}
