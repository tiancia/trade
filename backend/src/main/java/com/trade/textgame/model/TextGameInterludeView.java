package com.trade.textgame.model;

import java.util.List;

public record TextGameInterludeView(
        int turn,
        int completedSteps,
        int totalSteps,
        int currentDay,
        int nextStep,
        List<TextGameActionDefinition> actions,
        String recentFeedback,
        List<TextGameInterludeActionLogEntry> log
) {
}
