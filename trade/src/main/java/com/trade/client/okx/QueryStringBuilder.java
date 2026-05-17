package com.trade.client.okx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class QueryStringBuilder {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QueryStringBuilder() {
    }

    static String build(String path, Object req) {
        if (req == null) {
            return path;
        }

        Map<String, Object> params = OBJECT_MAPPER.convertValue(req, new TypeReference<>() {
        });
        StringBuilder result = new StringBuilder(path);
        boolean first = true;

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value.toString().isEmpty()) {
                continue;
            }

            result.append(first ? "?" : "&")
                    .append(encode(entry.getKey()))
                    .append("=")
                    .append(encode(value.toString()));
            first = false;
        }

        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
