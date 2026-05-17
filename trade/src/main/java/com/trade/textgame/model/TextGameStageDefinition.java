package com.trade.textgame.model;

public record TextGameStageDefinition(
        String id,
        String name,
        int turnStart,
        int turnEnd,
        String description
) {
}
