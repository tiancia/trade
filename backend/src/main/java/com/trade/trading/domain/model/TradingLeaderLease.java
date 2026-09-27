package com.trade.trading.domain.model;

import java.time.Instant;

/** Durable ownership snapshot for the trading single-writer lease. */
public record TradingLeaderLease(
        String leaseName,
        String ownerId,
        Instant leaseUntil,
        long fencingToken,
        Instant updatedAt
) {
}
