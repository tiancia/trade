package com.trade.trading.risk;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Durable fund-level stop state.
 *
 * <p>HALTED blocks all new live submissions even after process restart. Resume
 * requires an operator acknowledgement and an optimistic revision.</p>
 */
@Data
@Accessors(chain = true)
public class FundSafetyState {
    private String accountScope;
    private FundSafetyStatus status = FundSafetyStatus.ACTIVE;
    private String reason;
    private String source;
    private String resumeReason;
    private String lastActionError;
    private Instant haltedAt;
    private Instant resumedAt;
    private Instant updatedAt;
    private long version;

    public boolean isHalted() {
        return status == FundSafetyStatus.HALTED;
    }
}
