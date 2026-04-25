package com.trade.utils;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class UrlBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static String buildUrl(String baseUrl, Object req){
        if (req == null) {
            return baseUrl;
        }

        Map<String, Object> params = OBJECT_MAPPER.convertValue(
                req,
                new TypeReference<>() {}
        );

        StringBuilder url = new StringBuilder(baseUrl);
        boolean first = true;

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            String strValue = value.toString();
            if (strValue.isEmpty()) {
                continue;
            }

            url.append(first ? "?" : "&")
                    .append(encode(entry.getKey()))
                    .append("=")
                    .append(encode(strValue));
            first = false;
        }

        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
