package com.trade.client.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;

public record TelegramInlineKeyboardButton(String text, @JsonProperty("callback_data") String callbackData) {
    public TelegramInlineKeyboardButton {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Telegram inline button text is required");
        }
        if (callbackData == null || callbackData.isEmpty()
                || callbackData.getBytes(StandardCharsets.UTF_8).length > 64) {
            throw new IllegalArgumentException("Telegram callback data must contain 1 to 64 UTF-8 bytes");
        }
    }
}
