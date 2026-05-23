package com.trade.textgame.model;

import java.util.List;
import java.util.Map;

public record TextGameActionDefinition(
        String id,
        String label,
        String hint,
        Map<String, Integer> statsDelta,
        List<String> feedbackTemplates,
        Map<String, Integer> minStats,
        Map<String, Integer> maxStats
) {
    public TextGameActionDefinition {
        statsDelta = statsDelta == null ? Map.of() : Map.copyOf(statsDelta);
        feedbackTemplates = feedbackTemplates == null ? List.of() : List.copyOf(feedbackTemplates);
        minStats = minStats == null ? Map.of() : Map.copyOf(minStats);
        maxStats = maxStats == null ? Map.of() : Map.copyOf(maxStats);
    }
}
