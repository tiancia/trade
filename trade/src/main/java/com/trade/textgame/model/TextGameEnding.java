package com.trade.textgame.model;

import java.util.List;
import java.util.Map;

public record TextGameEnding(
        String title,
        String grade,
        String summary,
        List<String> echoes,
        Map<String, Integer> finalStats
) {
    public TextGameEnding withFinalStats(Map<String, Integer> stats) {
        return new TextGameEnding(title, grade, summary, echoes, stats);
    }
}
