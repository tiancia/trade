package com.trade.client.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(@JsonProperty("message_id") Long messageId, Long date,
                              TelegramChat chat, TelegramUser from, String text) {
}
