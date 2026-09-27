package com.trade.trading.application.port;

import com.trade.trading.domain.risk.FundSafetyState;

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
