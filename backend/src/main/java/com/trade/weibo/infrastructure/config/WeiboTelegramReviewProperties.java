package com.trade.weibo.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/** Telegram delivery belongs to the Weibo workflow, while transport settings belong to client. */
@Data
@ConfigurationProperties(prefix = "trade.weibo.review.telegram")
public class WeiboTelegramReviewProperties {
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
            throw new IllegalArgumentException("Weibo Telegram review requires a numeric, nonzero chat-id");
        }
    }

    public void validate(TelegramClientProperties client, WeiboWorkflowProperties workflow) {
        if (!workflow.isEnabled() || !isEnabled()) return;
        if (pollingFixedDelayMs < 1000) throw new IllegalArgumentException("Invalid Telegram review polling delay");
        if (!client.isEnabled()) {
            throw new IllegalArgumentException("Telegram review requires Telegram client enabled");
        }
        requiredChatId();
        if (reviewerUserIds == null || reviewerUserIds.isEmpty()
                || reviewerUserIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Telegram review requires numeric reviewer-user-ids");
        }
        if (workflow.getMaxBodyChars() > 3000) {
            throw new IllegalArgumentException("Telegram review requires max-body-chars at most 3000 to show the full post");
        }
        if (client.getConnectTimeoutSeconds() < 1 || client.getRequestTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Invalid Telegram review client timeouts");
        }
        client.requiredBotToken();
        client.normalizedBaseUrl();
    }
}
