package com.trade.trading.application;

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
