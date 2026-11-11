package com.trade.trading.domain.risk;

import java.math.BigDecimal;

/** Equity fallback and single-open exposure limits on resolved account facts. */
public final class AccountValuation {
    private AccountValuation() {}
    /** Only an omitted total permits fallback; zero and negative totals are authoritative. */
    public static BigDecimal equity(BigDecimal totalEquity, BigDecimal quote, BigDecimal base, BigDecimal price) {
        return totalEquity != null ? totalEquity : quote.add(base.multiply(price));
    }
    public static BigDecimal available(BigDecimal available, BigDecimal cash) {
        return available.signum() > 0 ? available : cash;
    }
    public static BigDecimal balance(BigDecimal available, BigDecimal cash, BigDecimal equity) {
        BigDecimal amount = available(available, cash);
        return amount.signum() > 0 ? amount : equity;
    }
    /** Null disables the cap; an enabled cap can be zero when equity is depleted. */
    public static BigDecimal singleOpenLimit(boolean enabled, BigDecimal ratio, BigDecimal equity) {
        if (!enabled || ratio == null || ratio.signum() <= 0) {
            return null;
        }
        return equity.max(BigDecimal.ZERO).multiply(ratio);
    }
}
