package com.trade.textgame.model;

public record TextGameResolutionView(
        String status,
        Integer turn,
        String error,
        boolean canAdvance
) {
}
