package com.trade.client.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TelegramApi {
    private final TelegramHttpClient client;

    public TelegramApi(TelegramHttpClient client) {
        this.client = Objects.requireNonNull(client, "Telegram HTTP client is required");
    }

    public TelegramUser getMe() {
        TelegramUser user = client.readResult(client.post("getMe", Map.of(), 0), TelegramUser.class);
        if (user == null || user.id() == null) {
            throw new IllegalStateException("Telegram getMe result is missing a user id");
        }
        return user;
    }

    public TelegramMessage sendMessage(String chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public TelegramMessage sendMessage(String chatId, String text, TelegramInlineKeyboardMarkup replyMarkup) {
        String targetChat = requiredText(chatId, "chatId is required");
        if (text == null || text.isEmpty() || codePointLength(text) > 4096) {
            throw new IllegalArgumentException("Telegram message text must contain 1 to 4096 characters");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("chat_id", targetChat);
        parameters.put("text", text);
        if (replyMarkup != null) {
            parameters.put("reply_markup", replyMarkup);
        }
        TelegramMessage message = client.readResult(
                client.post("sendMessage", parameters, 0),
                TelegramMessage.class
        );
        if (message == null || message.messageId() == null || message.chat() == null || message.chat().id() == null) {
            throw new IllegalStateException("Telegram sendMessage result is missing message or chat identifiers");
        }
        return message;
    }

    public List<TelegramUpdate> getUpdates(Long offset, int limit, int timeoutSeconds, List<String> allowedUpdates) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Telegram updates limit must be between 1 and 100");
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Telegram polling timeout must be nonnegative");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (offset != null) {
            parameters.put("offset", offset);
        }
        parameters.put("limit", limit);
        parameters.put("timeout", timeoutSeconds);
        if (allowedUpdates != null) {
            for (String updateType : allowedUpdates) {
                if (updateType == null || updateType.isBlank()) {
                    throw new IllegalArgumentException("Telegram allowed update types must not be blank");
                }
            }
            parameters.put("allowed_updates", List.copyOf(allowedUpdates));
        }
        JsonNode result = client.post("getUpdates", parameters, timeoutSeconds);
        if (!result.isArray()) {
            throw new IllegalStateException("Telegram getUpdates result must be an array");
        }
        List<TelegramUpdate> updates = new ArrayList<>(result.size());
        for (JsonNode item : result) {
            TelegramUpdate update = client.readResult(item, TelegramUpdate.class);
            if (update == null || update.updateId() == null) {
                throw new IllegalStateException("Telegram update is missing an update id");
            }
            updates.add(update);
        }
        return List.copyOf(updates);
    }

    public boolean answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        String queryId = requiredText(callbackQueryId, "callbackQueryId is required");
        if (text != null && codePointLength(text) > 200) {
            throw new IllegalArgumentException("Telegram callback text must contain at most 200 characters");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("callback_query_id", queryId);
        if (text != null) {
            parameters.put("text", text);
        }
        parameters.put("show_alert", showAlert);
        JsonNode result = client.post("answerCallbackQuery", parameters, 0);
        if (!result.isBoolean() || !result.booleanValue()) {
            throw new IllegalStateException("Telegram callback answer was not confirmed");
        }
        return true;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
