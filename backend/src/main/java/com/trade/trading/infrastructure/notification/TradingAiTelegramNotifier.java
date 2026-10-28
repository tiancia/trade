package com.trade.trading.infrastructure.notification;

import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.TelegramHttpClient;
import com.trade.trading.application.port.TradingAiNotifier;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class TradingAiTelegramNotifier implements TradingAiNotifier {
    private final TradingAiProperties properties;
    private final TelegramClientProperties shared;
    private final TelegramApi api;
    private final String xReviewChat;

    public TradingAiTelegramNotifier(TradingAiProperties properties, TelegramClientProperties shared,
                                    @Value("${trade.x.review.telegram.chat-id:}") String xReviewChat) {
        this.properties = properties;
        this.shared = shared;
        this.xReviewChat = xReviewChat;
        this.api = new TelegramApi(new TelegramHttpClient(properties.getTelegram().getClient()));
    }

    @Override public boolean enabled() { return properties.getTelegram().isEnabled(); }

    @Override public void validateConfiguration() {
        var config = properties.getTelegram();
        var client = config.getClient();
        if (!config.isEnabled() || !client.isEnabled() || config.getCooldownMs() < 3600000
                || client.getConnectTimeoutSeconds() < 1 || client.getRequestTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Trading AI Telegram transport is disabled or invalid");
        }
        String token = client.requiredBotToken();
        String sharedToken = shared.getBotToken();
        // Private chats with two bots have the same user chat ID, but distinct bot identities.
        if (sharedToken != null && !sharedToken.isBlank()
                && token.split(":", 2)[0].equals(sharedToken.trim().split(":", 2)[0])) {
            throw new IllegalArgumentException("Trading AI requires a different Telegram bot from X/Weibo");
        }
        try {
            long chat = Long.parseLong(config.getChatId().trim());
            if (chat == 0) throw new NumberFormatException();
            if (chat < 0 && xReviewChat != null && !xReviewChat.isBlank()
                    && chat == Long.parseLong(xReviewChat.trim())) {
                throw new IllegalArgumentException("Cannot share the X review group");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Trading AI Telegram chat-id must be numeric and nonzero");
        }
        client.normalizedBaseUrl();
    }

    @Override public void send(String message) {
        validateConfiguration();
        api.sendMessage(properties.getTelegram().getChatId().trim(), message);
    }
}
