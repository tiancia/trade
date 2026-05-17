package com.trade.textgame.model;

public record CreateTextGameSessionRequest(
        String themeId,
        String modeId
) {
}
