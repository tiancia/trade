package com.trade.textgame.model;

import java.util.Map;

public record TextGameInterludeActionLogEntry(
        int turn,
        int step,
        int day,
        String actionId,
        String actionLabel,
        String feedback,
        Map<String, Integer> statsDelta,
        Map<String, Integer> statsAfter,
        boolean settling
) {
}
