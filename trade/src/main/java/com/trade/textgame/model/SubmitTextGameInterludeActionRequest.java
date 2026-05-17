package com.trade.textgame.model;

public record SubmitTextGameInterludeActionRequest(
        String actionId,
        Integer turn,
        Integer step
) {
}
