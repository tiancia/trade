package com.trade.textgame.model;

import java.util.Map;
import java.util.UUID;

public record TextGameSessionResponse(
        UUID sessionId,
        String themeId,
        String modeId,
        String phase,
        int turn,
        int maxTurns,
        int day,
        TextGameStageView stage,
        Map<String, Integer> stats,
        String lastResult,
        TextGameScene scene,
        TextGameEnding ending,
        TextGameResolutionView resolution,
        TextGameInterludeView interlude,
        boolean completed
) {
}
