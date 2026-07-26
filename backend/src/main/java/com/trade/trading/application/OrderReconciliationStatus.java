package com.trade.trading.application;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class OrderReconciliationStatus {
    private boolean enabled;
    private boolean running;
    private Instant lastStartedAt;
    private Instant lastCompletedAt;
    private int lastCandidateCount;
    private int lastReconciledCount;
    private int consecutiveFailures;
    private String lastError;
}
