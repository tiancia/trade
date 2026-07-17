package com.trade.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;

/** End-of-candle portfolio snapshot used to inspect return and drawdown paths. */
public record BacktestEquityPoint(
        Instant candleTimestamp,
        BigDecimal markPrice,
        BigDecimal cash,
        BigDecimal baseAmount,
        BigDecimal equity,
        BigDecimal drawdown
) {
}
