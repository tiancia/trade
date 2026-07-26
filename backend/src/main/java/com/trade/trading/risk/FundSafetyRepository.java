package com.trade.trading.risk;

import java.time.Instant;

public interface FundSafetyRepository {
    FundSafetyState getOrCreate(String accountScope);

    FundSafetyState halt(String accountScope, String source, String reason, Instant haltedAt);

    FundSafetyState resume(
            String accountScope,
            long expectedVersion,
            String resumeReason,
            Instant resumedAt
    );

    FundSafetyState recordActionError(String accountScope, String error);
}
