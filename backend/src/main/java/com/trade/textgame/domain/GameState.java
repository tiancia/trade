package com.trade.textgame.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GameState {
    private Map<String, Integer> attributes = new LinkedHashMap<>();
    private Map<String, Integer> relations = new LinkedHashMap<>();
    private Map<String, Object> flags = new LinkedHashMap<>();
    private List<String> history = new ArrayList<>();

    public Map<String, Integer> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Integer> attributes) {
        this.attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
    }

    public Map<String, Integer> getRelations() {
        return relations;
    }

    public void setRelations(Map<String, Integer> relations) {
        this.relations = new LinkedHashMap<>(relations == null ? Map.of() : relations);
    }

    public Map<String, Object> getFlags() {
        return flags;
    }

    public void setFlags(Map<String, Object> flags) {
        this.flags = new LinkedHashMap<>(flags == null ? Map.of() : flags);
    }

    public List<String> getHistory() {
        return history;
    }

    public void setHistory(List<String> history) {
        this.history = new ArrayList<>(history == null ? List.of() : history);
    }
}
