package com.trade.trading.domain.backtest;

import java.math.BigDecimal;
import java.util.Map;

/** Request eligibility and financial assumptions for reproducible simulations. */
public final class BacktestPolicy {
    private static final int MAX_REQUEST_CANDLES = 50_000;
    public static String requireSelection(BacktestRequest source, boolean derivativeInstrument) {
        if (source == null) {
            throw new IllegalArgumentException("Backtest request is required");
        }
        String strategyId = trim(source.getStrategyId());
        if (strategyId == null) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (source.getFrom() == null || source.getTo() == null || !source.getFrom().isBefore(source.getTo())) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (derivativeInstrument) {
            throw new IllegalArgumentException(
                    "BacktestBroker currently supports SPOT only; derivative contract value and margin are not modeled"
            );
        }

        return strategyId;
    }
    public static BacktestRequest normalize(BacktestRequest source, String strategyId, String defaultInstrument, String defaultBar, Map<String,Object> overrides) {
        BigDecimal initialCash = source.getInitialCash() == null
                ? new BigDecimal("1000")
                : source.getInitialCash();
        BigDecimal feeRate = source.getFeeRate() == null
                ? new BigDecimal("0.001")
                : source.getFeeRate();
        BigDecimal slippageRate = source.getSlippageRate() == null
                ? BigDecimal.ZERO
                : source.getSlippageRate();
        requirePositive(initialCash, "initialCash");
        requireRate(feeRate, "feeRate");
        requireRate(slippageRate, "slippageRate");
        if (source.getMaxCandles() < 2 || source.getMaxCandles() > MAX_REQUEST_CANDLES) {
            throw new IllegalArgumentException("maxCandles must be between 2 and " + MAX_REQUEST_CANDLES);
        }

        String instId = defaultIfBlank(source.getInstId(), defaultInstrument);
        String bar = defaultIfBlank(source.getBar(), defaultBar);
        if (trim(instId) == null) {
            throw new IllegalArgumentException("instId is required");
        }
        if (trim(bar) == null) {
            throw new IllegalArgumentException("bar is required");
        }

        BacktestRequest normalized = new BacktestRequest()
                .setStrategyId(strategyId)
                .setInstId(instId)
                .setBar(bar)
                .setFrom(source.getFrom())
                .setTo(source.getTo())
                .setInitialCash(initialCash)
                .setFeeRate(feeRate)
                .setSlippageRate(slippageRate)
                .setForceCloseAtEnd(source.isForceCloseAtEnd())
                .setIncludeUnconfirmed(source.isIncludeUnconfirmed())
                .setMaxCandles(source.getMaxCandles())
                .setParameterOverrides(overrides);
        return normalized;
    }
    private static void requirePositive(BigDecimal value, String field) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
    private static void requireRate(BigDecimal value, String field) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(field + " must be greater than or equal to 0 and less than 1");
        }
    }
    private static String defaultIfBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized == null ? fallback : normalized;
    }
    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
