package com.trade.client.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public record TelegramInlineKeyboardMarkup(
        @JsonProperty("inline_keyboard") List<List<TelegramInlineKeyboardButton>> inlineKeyboard) {
    public TelegramInlineKeyboardMarkup {
        if (inlineKeyboard == null || inlineKeyboard.isEmpty()) {
            throw new IllegalArgumentException("Telegram inline keyboard must contain at least one row");
        }
        List<List<TelegramInlineKeyboardButton>> rows = new ArrayList<>(inlineKeyboard.size());
        for (List<TelegramInlineKeyboardButton> row : inlineKeyboard) {
            if (row == null || row.isEmpty() || row.stream().anyMatch(button -> button == null)) {
                throw new IllegalArgumentException("Telegram inline keyboard rows must contain nonnull buttons");
            }
            rows.add(List.copyOf(row));
        }
        inlineKeyboard = List.copyOf(rows);
    }
}
