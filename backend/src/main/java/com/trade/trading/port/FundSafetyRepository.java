package com.trade.trading.port;

import com.trade.trading.domain.risk.FundSafetyState;

import java.time.Instant;
import java.math.BigDecimal;

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

    String firstUnverifiedPosition(String accountScope, Instant haltedAt, BigDecimal tolerance, boolean dedicated);

    void recordOrderRecovery(String accountScope, long expectedVersion, Runnable recordRecovery);
}
