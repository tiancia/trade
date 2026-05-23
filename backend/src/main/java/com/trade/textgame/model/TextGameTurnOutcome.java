package com.trade.textgame.model;

import java.util.Map;

public record TextGameTurnOutcome(
        String result,
        Map<String, Integer> statsDelta,
        TextGameScene scene,
        TextGameEnding ending
) {
}
