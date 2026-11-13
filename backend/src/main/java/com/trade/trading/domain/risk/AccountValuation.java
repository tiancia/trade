package com.trade.trading.domain.risk;

import java.math.BigDecimal;

/** Equity fallback and single-open exposure limits on resolved account facts. */
public final class AccountValuation {
    private AccountValuation() {}
    /** Only an omitted total permits fallback; zero and negative totals are authoritative. */
    public static BigDecimal equity(BigDecimal totalEquity, BigDecimal quote, BigDecimal base, BigDecimal price) {
        return totalEquity != null ? totalEquity : quote.add(base.multiply(price));
    }
    /** Null means omitted; frozen funds and reported liabilities remain part of equity. */
    public static BigDecimal balance(BigDecimal available, BigDecimal cash, BigDecimal equity) {
        if (equity != null) return equity;
        if (cash != null) return cash;
        return available == null ? BigDecimal.ZERO : available;
    }
    /** Null disables the cap; an enabled cap can be zero when equity is depleted. */
    public static BigDecimal singleOpenLimit(boolean enabled, BigDecimal ratio, BigDecimal equity) {
        if (!enabled || ratio == null || ratio.signum() <= 0) {
            return null;
        }
        return equity.max(BigDecimal.ZERO).multiply(ratio);
    }
}
