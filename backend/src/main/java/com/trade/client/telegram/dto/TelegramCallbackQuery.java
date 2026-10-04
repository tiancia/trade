package com.trade.client.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramCallbackQuery(String id, TelegramUser from, TelegramMessage message,
                                   @JsonProperty("inline_message_id") String inlineMessageId,
                                   @JsonProperty("chat_instance") String chatInstance, String data) {
}
