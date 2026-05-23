package com.trade.client.polymarket;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

final class PolymarketQueryStringBuilder {
    private PolymarketQueryStringBuilder() {
    }

    static String build(String path, Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return path;
        }

        StringBuilder result = new StringBuilder(path);
        boolean first = true;
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (isEmpty(value)) {
                continue;
            }

            if (value instanceof Collection<?> values) {
                for (Object item : values) {
                    if (isEmpty(item)) {
                        continue;
                    }
                    first = append(result, first, entry.getKey(), item);
                }
            } else {
                first = append(result, first, entry.getKey(), value);
            }
        }
        return result.toString();
    }

    private static boolean append(StringBuilder result, boolean first, String key, Object value) {
        result.append(first ? "?" : "&")
                .append(encode(key))
                .append("=")
                .append(encode(String.valueOf(value)));
        return false;
    }

    private static boolean isEmpty(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
