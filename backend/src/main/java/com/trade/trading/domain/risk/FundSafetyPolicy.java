package com.trade.trading.domain.risk;

import static com.trade.trading.domain.model.TradingStateTimestamps.parseOrNull;

import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.TradingRiskState;
import java.time.Instant;
/** Pure persistent-stop activation and recovery eligibility rules. */
public final class FundSafetyPolicy {
    private FundSafetyPolicy() {}
    public static void requireResumeState(FundSafetyState current, long expectedVersion) {
        if (!current.isHalted()) {
            throw new IllegalStateException("Fund safety is already ACTIVE");
        }
        if (current.getVersion() != expectedVersion) {
            throw new java.util.ConcurrentModificationException(
                    "Fund safety revision changed from " + expectedVersion + " to " + current.getVersion()
            );
        }

    }
    public static void requireFreshSuccessfulReconciliation(FundSafetyState safetyState, TradingState tradingState) {
        TradingRiskState riskState = tradingState.getRiskState();
        Instant positionAt = parseOrNull(tradingState.getPositionLastReconciledAt());
        Instant riskAt = parseOrNull(riskState == null ? null : riskState.getLastReconciliationAt());
        Instant haltedAt = safetyState.getHaltedAt();
        boolean stalePosition = positionAt == null || (haltedAt != null && positionAt.isBefore(haltedAt));
        boolean staleRisk = riskAt == null || (haltedAt != null && riskAt.isBefore(haltedAt));
        boolean failed = riskState == null
                || riskState.getConsecutiveReconciliationFailures() != 0
                || riskState.getLastReconciliationError() != null;
        if (stalePosition || staleRisk || failed) {
            throw new IllegalStateException(
                    "Cannot resume before a successful order and position reconciliation after the fund stop"
            );
        }
    }
    public static void requireNoPendingOrders(boolean exchangePending, boolean localPending) {
        if (exchangePending) { throw new IllegalStateException("Cannot resume while OKX still has pending orders"); }
        if (localPending) { throw new IllegalStateException("Cannot resume while local orders still require reconciliation"); }
    }
}
