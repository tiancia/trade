package com.trade.client.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") Long updateId, TelegramMessage message,
                             @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery) {
}
