package com.trade.textgame.model;

public record TextGameThemeSummary(
        String id,
        String name,
        String description
) {
    public static TextGameThemeSummary from(TextGameThemeDefinition theme) {
        return new TextGameThemeSummary(theme.id(), theme.name(), theme.description());
    }
}
