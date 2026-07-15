package com.trade.trading.order;

import java.math.BigDecimal;

public record OrderSubmission(
        String idempotencyKey,
        String clientOrderId,
        String decisionId,
        String strategyId,
        String instId,
        String action,
        String side,
        String tdMode,
        String orderType,
        String targetCurrency,
        BigDecimal requestedSize
) {
    public OrderSubmission {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(clientOrderId, "clientOrderId");
        requireText(instId, "instId");
        requireText(action, "action");
        requireText(side, "side");
        if (requestedSize == null || requestedSize.signum() <= 0) {
            throw new IllegalArgumentException("requestedSize must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
