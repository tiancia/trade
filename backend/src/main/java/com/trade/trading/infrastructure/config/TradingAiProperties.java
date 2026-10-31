package com.trade.trading.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "trade.trading.ai")
public class TradingAiProperties {
    private volatile boolean enabled = false;
    private boolean autoApply = false;
    private long reviewIntervalMs = 1800000;
    private int targetMinClosedTradesPerDay = 5;
    private int targetMaxClosedTradesPerDay = 8;
    private int maxReviewAgeSeconds = 300;
    private int candleCount = 96;
    private BigDecimal minimumConfidence = new BigDecimal("0.80");
    private Telegram telegram = new Telegram();

    public void validate() {
        if (reviewIntervalMs < 900000 || reviewIntervalMs > 86400000
                || maxReviewAgeSeconds < 30 || maxReviewAgeSeconds > 600
                || candleCount < 32 || candleCount > 199
                || targetMinClosedTradesPerDay < 1 || targetMaxClosedTradesPerDay > 100
                || targetMaxClosedTradesPerDay < targetMinClosedTradesPerDay
                || minimumConfidence == null || minimumConfidence.compareTo(new BigDecimal("0.5")) < 0
                || minimumConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Invalid trading AI interval, freshness, candle count, daily target or confidence");
        }
    }

    @Data
    public static class Telegram {
        private Boolean enabled;
        private String chatId = "";
        private long cooldownMs = 86400000;
        // A separate transport and bot identity; never inherits trade.telegram credentials.
        private TelegramClientProperties client = new TelegramClientProperties();

        public boolean isEnabled() {
            return enabled != null ? enabled : chatId != null && !chatId.isBlank();
        }
    }
}
