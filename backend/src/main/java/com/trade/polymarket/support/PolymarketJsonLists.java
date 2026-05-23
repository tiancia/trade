package com.trade.polymarket.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class PolymarketJsonLists {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PolymarketJsonLists() {
    }

    public static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }

        JsonNode normalized = normalize(node);
        if (!normalized.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : normalized) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static JsonNode normalize(JsonNode node) {
        if (!node.isTextual()) {
            return node;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return node;
        }
        try {
            return OBJECT_MAPPER.readTree(text);
        } catch (Exception ignored) {
            return node;
        }
    }
}
