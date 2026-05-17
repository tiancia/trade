package com.trade.textgame.model;

import java.util.List;
import java.util.Map;

public record TextGameThemeDefinition(
        String id,
        String name,
        String description,
        String premise,
        String protagonistSetup,
        String tone,
        Map<String, Integer> initialStats,
        Map<String, String> statLabels,
        List<String> statRules,
        List<String> openingHooks,
        List<TextGameActionDefinition> interludeActions,
        List<TextGameActionDefinition> settlingActions
) {
}
