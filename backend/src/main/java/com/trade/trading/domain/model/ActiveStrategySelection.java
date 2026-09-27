package com.trade.trading.domain.model;

import java.time.Instant;

/** Durable operator-selected strategy used by scheduled trading decisions. */
public record ActiveStrategySelection(
        String strategyId,
        long revision,
        Instant changedAt
) {
}
