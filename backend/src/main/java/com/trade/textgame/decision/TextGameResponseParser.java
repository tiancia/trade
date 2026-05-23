package com.trade.textgame.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.model.TextGameChoice;
import com.trade.textgame.model.TextGameEnding;
import com.trade.textgame.model.TextGameScene;
import com.trade.textgame.model.TextGameTurnOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TextGameResponseParser {
    private final ObjectMapper objectMapper;

    public TextGameResponseParser() {
        this(new ObjectMapper());
    }

    public TextGameResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TextGameScene parseOpening(String rawResponse) {
        JsonNode root = readRoot(rawResponse, "AI text game opening response is empty");
        JsonNode sceneNode = firstExisting(root, "scene", "nextScene");
        if (sceneNode == null && looksLikeScene(root)) {
            sceneNode = root;
        }
        return readScene(sceneNode, "opening scene");
    }

    public TextGameTurnOutcome parseTurn(String rawResponse, boolean finalTurn) {
        JsonNode root = readRoot(rawResponse, "AI text game turn response is empty");
        String result = requiredText(root, "result", "lastResult", "consequence");
        Map<String, Integer> statsDelta = readStatsDelta(root);
        if (finalTurn) {
            TextGameEnding ending = readEnding(firstExisting(root, "ending", "finalEnding"));
            return new TextGameTurnOutcome(result, statsDelta, null, ending);
        }
        TextGameScene scene = readScene(firstExisting(root, "scene", "nextScene"), "next scene");
        return new TextGameTurnOutcome(result, statsDelta, scene, null);
    }

    private JsonNode readRoot(String rawResponse, String emptyMessage) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        try {
            return objectMapper.readTree(extractJsonObject(rawResponse));
        } catch (Exception e) {
            throw new IllegalArgumentException("Parse AI text game response failed: " + e.getMessage(), e);
        }
    }

    private static TextGameScene readScene(JsonNode node, String fieldLabel) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AI text game response missing " + fieldLabel);
        }
        String title = requiredText(node, "title");
        String text = requiredText(node, "text", "content", "body");
        List<TextGameChoice> choices = readChoices(firstExisting(node, "choices", "options"));
        if (choices.size() < 2 || choices.size() > 3) {
            throw new IllegalArgumentException("AI text game scene choices count must be 2 or 3");
        }
        return new TextGameScene(title, text, choices);
    }

    private static TextGameEnding readEnding(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AI text game response missing ending");
        }
        String title = requiredText(node, "title");
        String grade = requiredText(node, "grade");
        String summary = requiredText(node, "summary");
        List<String> echoes = readStringArray(firstExisting(node, "echoes", "choiceEchoes"));
        if (echoes.isEmpty()) {
            throw new IllegalArgumentException("AI text game ending echoes is required");
        }
        return new TextGameEnding(title, grade, summary, echoes, Map.of());
    }

    private static List<TextGameChoice> readChoices(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("AI text game response missing scene choices");
        }
        List<TextGameChoice> choices = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String id = requiredText(item, "id").trim().toUpperCase();
            String label = requiredText(item, "label", "text");
            String hint = optionalText(item, "hint");
            choices.add(new TextGameChoice(id, label, hint));
        }
        return choices;
    }

    private static Map<String, Integer> readStatsDelta(JsonNode root) {
        JsonNode node = firstExisting(root, "statsDelta", "statDelta", "statsChanges");
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AI text game response missing statsDelta");
        }
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (!value.isNumber() && !value.isTextual()) {
                throw new IllegalArgumentException("AI text game statsDelta must contain numbers");
            }
            try {
                values.put(entry.getKey(), Integer.parseInt(value.asText().trim()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("AI text game statsDelta value must be an integer");
            }
        });
        return Collections.unmodifiableMap(values);
    }

    private static List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText(null);
            if (hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static JsonNode firstExisting(JsonNode root, String... fieldNames) {
        if (root == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode node = root.get(fieldName);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String requiredText(JsonNode root, String... fieldNames) {
        String value = optionalText(root, fieldNames);
        if (!hasText(value)) {
            throw new IllegalArgumentException("AI text game response missing " + fieldNames[0]);
        }
        return value;
    }

    private static String optionalText(JsonNode root, String... fieldNames) {
        JsonNode node = firstExisting(root, fieldNames);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private static boolean looksLikeScene(JsonNode root) {
        return root != null
                && root.isObject()
                && firstExisting(root, "title") != null
                && firstExisting(root, "text", "content", "body") != null
                && firstExisting(root, "choices", "options") != null;
    }

    private static String extractJsonObject(String rawResponse) {
        String text = stripMarkdownFence(rawResponse);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String stripMarkdownFence(String rawResponse) {
        String text = rawResponse == null ? "" : rawResponse.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd >= 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
