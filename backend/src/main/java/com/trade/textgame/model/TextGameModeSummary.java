package com.trade.textgame.model;

public record TextGameModeSummary(
        String id,
        String name,
        String description,
        int maxTurns,
        int totalDays
) {
    public static TextGameModeSummary from(TextGameModeDefinition mode) {
        return new TextGameModeSummary(
                mode.id(),
                mode.name(),
                mode.description(),
                mode.maxTurns(),
                mode.totalDays()
        );
    }
}
