package com.trade.x.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.x.review.telegram")
public class XTelegramReviewProperties {
    private boolean enabled = false;
    private String chatId = "";
    private List<Long> reviewerUserIds = List.of();
    private long pollingFixedDelayMs = 5000;

    public long requiredChatId() {
        try {
            long value = Long.parseLong(chatId == null ? "" : chatId.trim());
            if (value == 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("X Telegram review requires a numeric, nonzero chat-id");
        }
    }

    public void validate(TelegramClientProperties client, XWorkflowProperties workflow) {
        if (pollingFixedDelayMs < 1000) throw new IllegalArgumentException("Invalid X Telegram polling delay");
        if (!enabled) return;
        if (!client.isEnabled() || !workflow.isEnabled()) {
            throw new IllegalArgumentException("X review requires Telegram client and X workflow enabled");
        }
        requiredChatId();
        if (reviewerUserIds == null || reviewerUserIds.isEmpty()
                || reviewerUserIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("X review requires numeric reviewer-user-ids");
        }
        if (client.getConnectTimeoutSeconds() < 1 || client.getRequestTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Invalid Telegram review timeouts");
        }
        client.requiredBotToken();
        client.normalizedBaseUrl();
    }
}
