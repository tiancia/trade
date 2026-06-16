package com.trade.textgame.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class TextGameRuleEngine {
    public boolean matches(JsonNode condition, GameState state) {
        if (condition == null || condition.isMissingNode() || condition.isNull() || condition.isEmpty()) {
            return true;
        }
        if (condition.has("all")) {
            for (JsonNode child : condition.path("all")) {
                if (!matches(child, state)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.has("any")) {
            for (JsonNode child : condition.path("any")) {
                if (matches(child, state)) {
                    return true;
                }
            }
            return false;
        }
        if (condition.has("not")) {
            return !matches(condition.path("not"), state);
        }

        String source = condition.path("source").asText();
        String key = condition.path("key").asText();
        String op = condition.path("op").asText();
        JsonNode expected = condition.get("value");
        Object actual = switch (source) {
            case "attribute" -> state.getAttributes().get(key);
            case "relation" -> state.getRelations().get(key);
            case "flag" -> state.getFlags().get(key);
            case "history" -> state.getHistory().contains(key);
            default -> null;
        };
        return compare(actual, op, expected);
    }

    public EffectResult applyEffects(JsonNode effects, GameState state) {
        Map<String, Integer> attributeDelta = new LinkedHashMap<>();
        Map<String, Integer> relationDelta = new LinkedHashMap<>();
        Map<String, Object> flagChanges = new LinkedHashMap<>();
        if (effects == null || !effects.isArray()) {
            return new EffectResult(attributeDelta, relationDelta, flagChanges);
        }
        for (JsonNode effect : effects) {
            String target = effect.path("target").asText();
            String key = effect.path("key").asText();
            String op = effect.path("op").asText();
            JsonNode value = effect.get("value");
            switch (target) {
                case "attribute" -> applyNumber(state.getAttributes(), attributeDelta, key, op, value.asInt());
                case "relation" -> applyNumber(state.getRelations(), relationDelta, key, op, value.asInt());
                case "flag" -> {
                    Object converted = jsonValue(value);
                    if ("set".equals(op)) {
                        state.getFlags().put(key, converted);
                        flagChanges.put(key, converted);
                    }
                }
                default -> throw new IllegalArgumentException("不支持的效果目标: " + target);
            }
        }
        return new EffectResult(attributeDelta, relationDelta, flagChanges);
    }

    public String resolveTransition(JsonNode choice, GameState state) {
        for (JsonNode transition : choice.path("transitions")) {
            if (!transition.has("when") || matches(transition.path("when"), state)) {
                return transition.path("to").asText();
            }
        }
        throw new IllegalStateException("选项没有匹配的剧情转移: " + choice.path("id").asText());
    }

    public List<String> resolveText(JsonNode owner, GameState state, String field) {
        JsonNode variants = owner.path(field + "Variants");
        if (variants.isArray()) {
            for (JsonNode variant : variants) {
                if (matches(variant.path("when"), state)) {
                    return textList(variant.path("text"));
                }
            }
        }
        return textList(owner.path(field));
    }

    public List<JsonNode> visibleChoices(JsonNode scene, GameState state) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode choice : scene.path("choices")) {
            if (matches(choice.path("visibleWhen"), state)) {
                result.add(choice);
            }
        }
        return result;
    }

    private static void applyNumber(
            Map<String, Integer> values,
            Map<String, Integer> deltas,
            String key,
            String op,
            int operand
    ) {
        int before = values.getOrDefault(key, 0);
        int after = switch (op) {
            case "add" -> before + operand;
            case "set" -> operand;
            default -> throw new IllegalArgumentException("不支持的数字效果操作: " + op);
        };
        values.put(key, after);
        deltas.merge(key, after - before, Integer::sum);
    }

    private static boolean compare(Object actual, String op, JsonNode expectedNode) {
        Object expected = jsonValue(expectedNode);
        return switch (op) {
            case "eq" -> Objects.equals(normalize(actual), normalize(expected));
            case "neq" -> !Objects.equals(normalize(actual), normalize(expected));
            case "gt" -> number(actual) > number(expected);
            case "gte" -> number(actual) >= number(expected);
            case "lt" -> number(actual) < number(expected);
            case "lte" -> number(actual) <= number(expected);
            case "contains" -> actual instanceof Boolean b ? b : actual instanceof List<?> list && list.contains(expected);
            default -> false;
        };
    }

    private static Object normalize(Object value) {
        return value instanceof Number number ? number.doubleValue() : value;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.asText();
    }

    private static List<String> textList(JsonNode node) {
        if (!(node instanceof ArrayNode array)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    public record EffectResult(
            Map<String, Integer> attributeDelta,
            Map<String, Integer> relationDelta,
            Map<String, Object> flagChanges
    ) {
    }
}
