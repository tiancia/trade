package com.trade.trading.persistence;

import com.trade.trading.risk.FundSafetyStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class FundSafetyStateRow {
    private String accountScope;
    private FundSafetyStatus status;
    private String reason;
    private String source;
    private String resumeReason;
    private String lastActionError;
    private Instant haltedAt;
    private Instant resumedAt;
    private Instant updatedAt;
    private long version;
}
