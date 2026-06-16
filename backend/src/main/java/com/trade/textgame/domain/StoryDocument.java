package com.trade.textgame.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StoryDocument {
    private final ObjectNode root;
    private final Map<String, JsonNode> nodes;

    private StoryDocument(ObjectNode root, Map<String, JsonNode> nodes) {
        this.root = root;
        this.nodes = Map.copyOf(nodes);
    }

    public static StoryDocument parse(ObjectMapper objectMapper, String json) {
        try {
            return from(objectMapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("剧情 JSON 无法解析: " + e.getMessage(), e);
        }
    }

    public static StoryDocument from(JsonNode value) {
        if (!(value instanceof ObjectNode root)) {
            throw new IllegalArgumentException("剧情 JSON 根节点必须是对象");
        }
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        JsonNode nodeArray = root.path("nodes");
        if (nodeArray.isArray()) {
            for (JsonNode node : nodeArray) {
                String id = node.path("id").asText("");
                if (!id.isBlank()) {
                    nodes.putIfAbsent(id, node);
                }
            }
        }
        return new StoryDocument(root, nodes);
    }

    public ObjectNode root() {
        return root;
    }

    public JsonNode node(String id) {
        JsonNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("剧情节点不存在: " + id);
        }
        return node;
    }

    public Map<String, JsonNode> nodes() {
        return nodes;
    }

    public String storyKey() {
        return root.path("storyKey").asText();
    }

    public String startNodeId() {
        return root.path("startNodeId").asText();
    }

    public JsonNode metadata() {
        return root.path("metadata");
    }

    public JsonNode initialState() {
        return root.path("initialState");
    }
}
