package com.trade.textgame.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.textgame.domain.StoryValidation.Issue;
import com.trade.textgame.domain.StoryValidation.Result;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TextGameStoryValidator {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "storyKey", "metadata", "initialState", "startNodeId", "nodes");
    private static final Set<String> METADATA_FIELDS = Set.of("title", "summary", "durationMinutes", "maxChoices", "chapterCount", "tags", "coverImage");
    private static final Set<String> INITIAL_STATE_FIELDS = Set.of("attributes", "relations", "flags");
    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "chapter", "date", "title", "grade", "text", "textVariants", "echoes", "choices");
    private static final Set<String> CHAPTER_FIELDS = Set.of("number", "title");
    private static final Set<String> CHOICE_FIELDS = Set.of("id", "label", "hint", "visibleWhen", "enabledWhen", "disabledReason", "resultText", "resultTextVariants", "effects", "transitions");
    private static final Set<String> EFFECT_FIELDS = Set.of("target", "key", "op", "value");
    private static final Set<String> TRANSITION_FIELDS = Set.of("when", "to");
    private static final Set<String> CONDITION_FIELDS = Set.of("all", "any", "not", "source", "key", "op", "value");
    private static final Set<String> TEXT_VARIANT_FIELDS = Set.of("when", "text");
    private static final Set<String> SOURCES = Set.of("attribute", "relation", "flag", "history");
    private static final Set<String> OPERATORS = Set.of("eq", "neq", "gt", "gte", "lt", "lte", "contains");
    private static final Set<String> TARGETS = Set.of("attribute", "relation", "flag");
    private static final Set<String> EFFECT_OPERATORS = Set.of("add", "set");

    public Result validate(JsonNode root) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (root == null || !root.isObject()) {
            errors.add(error("INVALID_ROOT", "$", "剧情 JSON 根节点必须是对象"));
            return Result.of(errors, warnings);
        }
        rejectUnknownFields(root, ROOT_FIELDS, "$", errors);
        requiredText(root, "storyKey", "$.storyKey", errors);
        requiredText(root, "startNodeId", "$.startNodeId", errors);
        if (!root.path("metadata").isObject()) {
            errors.add(error("MISSING_METADATA", "$.metadata", "metadata 必须是对象"));
        } else {
            rejectUnknownFields(root.path("metadata"), METADATA_FIELDS, "$.metadata", errors);
            requiredText(root.path("metadata"), "title", "$.metadata.title", errors);
            requiredText(root.path("metadata"), "summary", "$.metadata.summary", errors);
            if (root.path("metadata").path("maxChoices").asInt(0) <= 0) {
                errors.add(error("INVALID_MAX_CHOICES", "$.metadata.maxChoices", "maxChoices 必须大于 0"));
            }
        }
        if (!root.path("initialState").isObject()) {
            errors.add(error("MISSING_INITIAL_STATE", "$.initialState", "initialState 必须是对象"));
        } else {
            rejectUnknownFields(root.path("initialState"), INITIAL_STATE_FIELDS, "$.initialState", errors);
            validateIntegerMap(root.path("initialState").path("attributes"), "$.initialState.attributes", errors);
            validateIntegerMap(root.path("initialState").path("relations"), "$.initialState.relations", errors);
        }
        JsonNode nodeArray = root.path("nodes");
        if (!nodeArray.isArray() || nodeArray.isEmpty()) {
            errors.add(error("MISSING_NODES", "$.nodes", "nodes 必须是非空数组"));
            return Result.of(errors, warnings);
        }

        Map<String, JsonNode> nodes = new HashMap<>();
        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> endings = new HashSet<>();
        int index = 0;
        for (JsonNode node : nodeArray) {
            String path = "$.nodes[" + index + "]";
            rejectUnknownFields(node, NODE_FIELDS, path, errors);
            String id = requiredText(node, "id", path + ".id", errors);
            String type = requiredText(node, "type", path + ".type", errors);
            if (!id.isBlank() && nodes.put(id, node) != null) {
                errors.add(error("DUPLICATE_NODE", path + ".id", "重复节点 id: " + id));
            }
            validateText(node.path("text"), path + ".text", errors);
            validateTextVariants(node.path("textVariants"), path + ".textVariants", errors);
            if ("scene".equals(type)) {
                validateScene(node, path, edges.computeIfAbsent(id, ignored -> new HashSet<>()), errors);
            } else if ("ending".equals(type)) {
                endings.add(id);
                requiredText(node, "title", path + ".title", errors);
                requiredText(node, "grade", path + ".grade", errors);
            } else {
                errors.add(error("INVALID_NODE_TYPE", path + ".type", "节点类型只允许 scene 或 ending"));
            }
            index++;
        }

        String start = root.path("startNodeId").asText();
        if (!nodes.containsKey(start)) {
            errors.add(error("DANGLING_START", "$.startNodeId", "起始节点不存在: " + start));
        }
        for (Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            for (String target : entry.getValue()) {
                if (!nodes.containsKey(target)) {
                    errors.add(error("DANGLING_REFERENCE", "$.nodes", entry.getKey() + " 引用了不存在的节点 " + target));
                }
            }
        }
        if (endings.isEmpty()) {
            errors.add(error("NO_ENDING", "$.nodes", "剧情至少需要一个结局节点"));
        }
        if (nodes.containsKey(start)) {
            Set<String> reachable = reachableFrom(start, edges);
            for (String id : nodes.keySet()) {
                if (!reachable.contains(id)) {
                    errors.add(error("UNREACHABLE_NODE", "$.nodes", "节点不可达: " + id));
                }
            }
            if (hasCycle(start, edges, new HashSet<>(), new HashSet<>())) {
                errors.add(error("CYCLE_NOT_ALLOWED", "$.nodes", "v1 发布剧情不允许循环"));
            }
            Set<String> canEnd = nodesThatCanReachEnding(nodes.keySet(), edges, endings);
            for (String id : reachable) {
                if (!endings.contains(id) && !canEnd.contains(id)) {
                    errors.add(error("NO_ENDING_PATH", "$.nodes", "节点没有通往结局的路径: " + id));
                }
            }
        }
        return Result.of(errors, warnings);
    }

    private void validateScene(JsonNode node, String path, Set<String> targets, List<Issue> errors) {
        requiredText(node, "title", path + ".title", errors);
        if (!node.path("chapter").isObject()) {
            errors.add(error("MISSING_CHAPTER", path + ".chapter", "场景必须包含章节信息"));
        } else {
            rejectUnknownFields(node.path("chapter"), CHAPTER_FIELDS, path + ".chapter", errors);
        }
        requiredText(node, "date", path + ".date", errors);
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.size() < 2 || choices.size() > 4) {
            errors.add(error("INVALID_CHOICE_COUNT", path + ".choices", "每个场景必须有 2 到 4 个选项"));
            return;
        }
        Set<String> choiceIds = new HashSet<>();
        for (int i = 0; i < choices.size(); i++) {
            JsonNode choice = choices.get(i);
            String choicePath = path + ".choices[" + i + "]";
            rejectUnknownFields(choice, CHOICE_FIELDS, choicePath, errors);
            String choiceId = requiredText(choice, "id", choicePath + ".id", errors);
            requiredText(choice, "label", choicePath + ".label", errors);
            if (!choiceIds.add(choiceId)) {
                errors.add(error("DUPLICATE_CHOICE", choicePath + ".id", "同一场景内选项 id 重复: " + choiceId));
            }
            validateCondition(choice.get("visibleWhen"), choicePath + ".visibleWhen", errors);
            validateCondition(choice.get("enabledWhen"), choicePath + ".enabledWhen", errors);
            validateText(choice.path("resultText"), choicePath + ".resultText", errors);
            validateTextVariants(choice.path("resultTextVariants"), choicePath + ".resultTextVariants", errors);
            validateEffects(choice.path("effects"), choicePath + ".effects", errors);
            JsonNode transitions = choice.path("transitions");
            if (!transitions.isArray() || transitions.isEmpty()) {
                errors.add(error("MISSING_TRANSITION", choicePath + ".transitions", "选项必须包含至少一个转移"));
                continue;
            }
            boolean fallbackSeen = false;
            for (int j = 0; j < transitions.size(); j++) {
                JsonNode transition = transitions.get(j);
                String transitionPath = choicePath + ".transitions[" + j + "]";
                rejectUnknownFields(transition, TRANSITION_FIELDS, transitionPath, errors);
                String to = requiredText(transition, "to", transitionPath + ".to", errors);
                targets.add(to);
                if (transition.has("when")) {
                    if (fallbackSeen) {
                        errors.add(error("TRANSITION_AFTER_FALLBACK", transitionPath, "无条件转移必须排在最后"));
                    }
                    validateCondition(transition.get("when"), transitionPath + ".when", errors);
                } else {
                    fallbackSeen = true;
                    if (j != transitions.size() - 1) {
                        errors.add(error("FALLBACK_NOT_LAST", transitionPath, "无条件转移必须排在最后"));
                    }
                }
            }
            if (!fallbackSeen) {
                errors.add(error("MISSING_FALLBACK", choicePath + ".transitions", "转移列表必须以无条件转移兜底"));
            }
        }
    }

    private void validateEffects(JsonNode effects, String path, List<Issue> errors) {
        if (!effects.isArray()) {
            errors.add(error("INVALID_EFFECTS", path, "effects 必须是数组"));
            return;
        }
        for (int i = 0; i < effects.size(); i++) {
            JsonNode effect = effects.get(i);
            String itemPath = path + "[" + i + "]";
            rejectUnknownFields(effect, EFFECT_FIELDS, itemPath, errors);
            String target = effect.path("target").asText();
            String op = effect.path("op").asText();
            if (!TARGETS.contains(target)) {
                errors.add(error("INVALID_EFFECT_TARGET", itemPath + ".target", "非法效果目标: " + target));
            }
            if (!EFFECT_OPERATORS.contains(op) || ("flag".equals(target) && !"set".equals(op))) {
                errors.add(error("INVALID_EFFECT_OPERATOR", itemPath + ".op", "非法效果操作: " + op));
            }
            requiredText(effect, "key", itemPath + ".key", errors);
            if (!effect.has("value") || (("attribute".equals(target) || "relation".equals(target)) && !effect.path("value").isInt())) {
                errors.add(error("INVALID_EFFECT_VALUE", itemPath + ".value", "属性和关系效果值必须是整数"));
            }
        }
    }

    private void validateCondition(JsonNode condition, String path, List<Issue> errors) {
        if (condition == null || condition.isNull() || condition.isMissingNode()) {
            return;
        }
        if (!condition.isObject()) {
            errors.add(error("INVALID_CONDITION", path, "条件必须是对象"));
            return;
        }
        rejectUnknownFields(condition, CONDITION_FIELDS, path, errors);
        int combinators = (condition.has("all") ? 1 : 0) + (condition.has("any") ? 1 : 0) + (condition.has("not") ? 1 : 0);
        if (combinators > 0) {
            if (combinators != 1) {
                errors.add(error("INVALID_CONDITION", path, "条件组合只能包含 all、any、not 之一"));
                return;
            }
            if (condition.has("not")) {
                validateCondition(condition.get("not"), path + ".not", errors);
            } else {
                String field = condition.has("all") ? "all" : "any";
                JsonNode children = condition.path(field);
                if (!children.isArray() || children.isEmpty()) {
                    errors.add(error("INVALID_CONDITION", path + "." + field, field + " 必须是非空数组"));
                } else {
                    for (int i = 0; i < children.size(); i++) {
                        validateCondition(children.get(i), path + "." + field + "[" + i + "]", errors);
                    }
                }
            }
            return;
        }
        String source = condition.path("source").asText();
        String op = condition.path("op").asText();
        if (!SOURCES.contains(source)) {
            errors.add(error("INVALID_CONDITION_SOURCE", path + ".source", "非法条件来源: " + source));
        }
        if (!OPERATORS.contains(op) || ("history".equals(source) && !Set.of("contains", "eq", "neq").contains(op))) {
            errors.add(error("INVALID_CONDITION_OPERATOR", path + ".op", "非法比较操作: " + op));
        }
        requiredText(condition, "key", path + ".key", errors);
        if (!condition.has("value") && !"history".equals(source)) {
            errors.add(error("MISSING_CONDITION_VALUE", path + ".value", "条件缺少比较值"));
        }
    }

    private void validateTextVariants(JsonNode variants, String path, List<Issue> errors) {
        if (variants.isMissingNode()) {
            return;
        }
        if (!variants.isArray()) {
            errors.add(error("INVALID_TEXT_VARIANTS", path, "文本变体必须是数组"));
            return;
        }
        for (int i = 0; i < variants.size(); i++) {
            rejectUnknownFields(variants.get(i), TEXT_VARIANT_FIELDS, path + "[" + i + "]", errors);
            validateCondition(variants.get(i).get("when"), path + "[" + i + "].when", errors);
            validateText(variants.get(i).path("text"), path + "[" + i + "].text", errors);
        }
    }

    private void validateText(JsonNode text, String path, List<Issue> errors) {
        if (!text.isArray() || text.size() < 2 || text.size() > 4) {
            errors.add(error("INVALID_TEXT_BEATS", path, "文本必须包含 2 到 4 个短段落"));
            return;
        }
        for (int i = 0; i < text.size(); i++) {
            String value = text.get(i).asText();
            if (value.isBlank() || value.length() > 500) {
                errors.add(error("TEXT_TOO_LONG", path + "[" + i + "]", "段落不能为空且不能超过 500 字"));
            }
        }
    }

    private static void validateIntegerMap(JsonNode value, String path, List<Issue> errors) {
        if (!value.isObject()) {
            errors.add(error("INVALID_STATE_MAP", path, "状态字段必须是整数对象"));
            return;
        }
        value.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isInt()) {
                errors.add(error("INVALID_STATE_VALUE", path + "." + entry.getKey(), "状态初始值必须是整数"));
            }
        });
    }

    private static void rejectUnknownFields(JsonNode value, Set<String> allowed, String path, List<Issue> errors) {
        if (!value.isObject()) {
            return;
        }
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                errors.add(error("ILLEGAL_FIELD", path + "." + field, "不支持的字段: " + field));
            }
        });
    }

    private static String requiredText(JsonNode owner, String field, String path, List<Issue> errors) {
        String value = owner.path(field).asText("");
        if (value.isBlank()) {
            errors.add(error("MISSING_FIELD", path, field + " 不能为空"));
        }
        return value;
    }

    private static Set<String> reachableFrom(String start, Map<String, Set<String>> edges) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (visited.add(id)) {
                pending.addAll(edges.getOrDefault(id, Set.of()));
            }
        }
        return visited;
    }

    private static boolean hasCycle(String id, Map<String, Set<String>> edges, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            return true;
        }
        if (!visited.add(id)) {
            return false;
        }
        visiting.add(id);
        for (String next : edges.getOrDefault(id, Set.of())) {
            if (hasCycle(next, edges, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        return false;
    }

    private static Set<String> nodesThatCanReachEnding(Set<String> nodes, Map<String, Set<String>> edges, Set<String> endings) {
        Map<String, Set<String>> reverse = new HashMap<>();
        edges.forEach((from, tos) -> tos.forEach(to -> reverse.computeIfAbsent(to, ignored -> new HashSet<>()).add(from)));
        Set<String> result = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(endings);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (result.add(id)) {
                pending.addAll(reverse.getOrDefault(id, Set.of()));
            }
        }
        result.retainAll(nodes);
        return result;
    }

    private static Issue error(String code, String path, String message) {
        return new Issue("error", code, path, message);
    }
}
