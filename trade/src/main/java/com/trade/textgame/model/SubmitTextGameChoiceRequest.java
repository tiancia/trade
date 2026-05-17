package com.trade.textgame.model;

public record SubmitTextGameChoiceRequest(
        String choiceId,
        Integer turn
) {
}
