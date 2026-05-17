package com.trade.trading.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TradingMath {
    private TradingMath() {
    }

    public static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    public static BigDecimal percentChange(BigDecimal current, BigDecimal base) {
        if (current == null || base == null || base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(base).divide(base, 10, RoundingMode.HALF_UP);
    }

    public static BigDecimal clamp(BigDecimal value, BigDecimal upperBound) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (upperBound == null || upperBound.signum() <= 0) {
            return value;
        }
        return value.min(upperBound);
    }

    public static BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (step == null || step.signum() <= 0) {
            return value;
        }
        return value.divideToIntegralValue(step).multiply(step);
    }

    public static String plain(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        return normalized.toPlainString();
    }
}
