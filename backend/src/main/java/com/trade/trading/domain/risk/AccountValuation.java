package com.trade.trading.domain.risk;

import java.math.BigDecimal;

/** Equity fallback and single-open exposure limits on resolved account facts. */
public final class AccountValuation {
    private AccountValuation() {}
    public static BigDecimal equity(BigDecimal totalEquity, BigDecimal quote, BigDecimal base, BigDecimal price) {
        return totalEquity.signum() > 0 ? totalEquity : quote.add(base.multiply(price));
    }
    public static BigDecimal available(BigDecimal available, BigDecimal cash) {
        return available.signum() > 0 ? available : cash;
    }
    public static BigDecimal balance(BigDecimal available, BigDecimal cash, BigDecimal equity) {
        BigDecimal amount = available(available, cash);
        return amount.signum() > 0 ? amount : equity;
    }
    public static BigDecimal singleOpenLimit(boolean enabled, BigDecimal ratio, BigDecimal equity) {
        if (!enabled || ratio == null || ratio.signum() <= 0 || equity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return equity.multiply(ratio);
    }
}
