package com.trade.trading.domain.risk;

import java.math.BigDecimal;
/** Exposure ownership and repeated-reconciliation failure rules. */
public final class ReconciliationPolicy {
    private ReconciliationPolicy() {}
    public static boolean shouldHalt(int failures, int maximum) { return failures >= Math.max(1, maximum); }
    public static String positionMismatch(boolean dedicated, BigDecimal managed, BigDecimal exchange, BigDecimal tolerance) {
        if (!dedicated || zero(managed).subtract(zero(exchange)).abs().compareTo(zero(tolerance)) <= 0) { return null; }
        return "Dedicated-account position mismatch: managed=" + managed + ", exchange=" + exchange;
    }
    public static void requireSinglePosition(int count) {
        if (count > 1) { throw new IllegalStateException("Multiple derivative position sides require an explicit portfolio projection"); }
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
