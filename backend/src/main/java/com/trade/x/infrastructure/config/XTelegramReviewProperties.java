package com.trade.x.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.x.review.telegram")
public class XTelegramReviewProperties {
    private Boolean enabled;
    private String chatId = "";
    private List<Long> reviewerUserIds = List.of();
    private long pollingFixedDelayMs = 5000;

    public boolean isEnabled() {
        return enabled != null ? enabled : chatId != null && !chatId.isBlank();
    }

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
        validate(client, workflow.isEnabled());
    }

    public void validate(TelegramClientProperties client, boolean workflowEnabled) {
        if (!workflowEnabled || !isEnabled()) return;
        if (pollingFixedDelayMs < 1000) throw new IllegalArgumentException("Invalid X Telegram polling delay");
        if (!client.isEnabled()) {
            throw new IllegalArgumentException("X review requires Telegram client enabled");
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
