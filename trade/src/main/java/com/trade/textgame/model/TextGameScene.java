package com.trade.textgame.model;

import java.util.List;

public record TextGameScene(
        String title,
        String text,
        List<TextGameChoice> choices
) {
}
