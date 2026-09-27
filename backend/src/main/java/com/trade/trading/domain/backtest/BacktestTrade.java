package com.trade.trading.domain.backtest;

import com.trade.trading.domain.model.TradingAction;

import java.math.BigDecimal;
import java.time.Instant;

/** One simulated fill, independent of the broker implementation. */
public record BacktestTrade(
        String runId,
        String strategyId,
        TradingAction action,
        Instant timestamp,
        BigDecimal price,
        BigDecimal baseAmount,
        BigDecimal quoteAmount,
        BigDecimal fee,
        String reason,
        FillPriceSource fillPriceSource,
        BigDecimal realizedPnl,
        BigDecimal cashAfter,
        BigDecimal baseAfter
) {
    public BacktestTrade withRunId(String value) {
        return new BacktestTrade(
                value,
                strategyId,
                action,
                timestamp,
                price,
                baseAmount,
                quoteAmount,
                fee,
                reason,
                fillPriceSource,
                realizedPnl,
                cashAfter,
                baseAfter
        );
    }
}
