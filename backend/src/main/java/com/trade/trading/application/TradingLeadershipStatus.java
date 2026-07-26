package com.trade.trading.application;

import java.time.Instant;

/** Operator-facing state for database-backed trading leadership. */
public record TradingLeadershipStatus(
        boolean enabled,
        boolean running,
        boolean leader,
        String leaseName,
        String localOwnerId,
        String currentOwnerId,
        Long fencingToken,
        Instant leaseUntil,
        Instant lastAttemptAt,
        Instant lastSuccessfulRenewalAt,
        int consecutiveFailures,
        String lastError
) {
}
