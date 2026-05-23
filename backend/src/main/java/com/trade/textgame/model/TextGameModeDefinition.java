package com.trade.textgame.model;

import java.util.List;

public record TextGameModeDefinition(
        String id,
        String name,
        String description,
        int maxTurns,
        int totalDays,
        List<TextGameStageDefinition> stages
) {
    public int dayForTurn(int turn) {
        if (maxTurns <= 0 || totalDays <= 0) {
            return 0;
        }
        int clampedTurn = Math.max(0, Math.min(maxTurns, turn));
        return (int) Math.round(totalDays * (clampedTurn / (double) maxTurns));
    }

    public TextGameStageDefinition stageForTurn(int turn) {
        int stageTurn = Math.max(1, Math.min(maxTurns, turn <= 0 ? 1 : turn));
        for (TextGameStageDefinition stage : stages) {
            if (stageTurn >= stage.turnStart() && stageTurn <= stage.turnEnd()) {
                return stage;
            }
        }
        return stages.getLast();
    }
}
