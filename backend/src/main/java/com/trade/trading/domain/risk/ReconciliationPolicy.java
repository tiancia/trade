package com.trade.trading.domain.risk;

import java.math.BigDecimal;
/** Exposure ownership and repeated-reconciliation failure rules. */
public final class ReconciliationPolicy {
    private ReconciliationPolicy() {}
    public static boolean shouldHalt(int failures, int maximum) { return failures >= Math.max(1, maximum); }
    public static String positionMismatch(boolean dedicated, BigDecimal managed, BigDecimal exchange, BigDecimal tolerance) {
        BigDecimal shortfall = zero(managed).subtract(zero(exchange));
        if (dedicated) {
            return shortfall.abs().compareTo(zero(tolerance)) > 0
                    ? "Dedicated-account position mismatch: managed=" + managed + ", exchange=" + exchange
                    : null;
        }
        // Shared accounts may hold additional manual assets, but cannot back a
        // managed ledger larger than the total exchange balance. Do not let a
        // missing asset pass reconciliation and authorize a later fund resume.
        return shortfall.compareTo(zero(tolerance)) > 0
                ? "Managed position exceeds exchange holdings: managed=" + managed + ", exchange=" + exchange
                : null;
    }
    public static void requireSinglePosition(int count) {
        if (count > 1) { throw new IllegalStateException("Multiple derivative position sides require an explicit portfolio projection"); }
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
