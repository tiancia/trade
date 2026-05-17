package com.trade.textgame.model;

public record TextGameStageView(
        String id,
        String name
) {
    public static TextGameStageView from(TextGameStageDefinition stage) {
        return new TextGameStageView(stage.id(), stage.name());
    }
}
