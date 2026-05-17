package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TradingStrategyState {
    private String bias = "NEUTRAL";
    private String thesis;
    private String invalidation;
    private String horizon;
    private String updatedAt;
    private String sourceDecisionId;

    public boolean hasContent() {
        return hasText(bias)
                || hasText(thesis)
                || hasText(invalidation)
                || hasText(horizon);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
