package com.trade.automation;

import java.time.Instant;

public record AutomationLoopStatus(
        String id,
        long initialDelayMs,
        long fixedDelayMs,
        Instant nextRunAt,
        Instant lastRunStartedAt,
        Instant lastRunCompletedAt,
        Boolean lastRunSuccessful,
        String lastError
) {
}
