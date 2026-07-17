package com.trade.trading.backtest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.trade.trading.execution.BacktestBroker;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Accessors(chain = true)
public class BacktestRun {
    private String runId;
    private volatile BacktestStatus status = BacktestStatus.QUEUED;
    private BacktestRequest request;
    private Instant createdAt = Instant.now();
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile Instant firstCandleAt;
    private volatile Instant lastCandleAt;
    private volatile BigDecimal totalReturn;
    private volatile BigDecimal benchmarkReturn;
    private volatile BigDecimal maxDrawdown;
    private volatile BigDecimal winRate;
    private volatile BigDecimal profitFactor;
    private volatile BigDecimal totalFees;
    private volatile BigDecimal realizedPnl;
    private volatile BigDecimal unrealizedPnl;
    private volatile int candleCount;
    private volatile int processedCandleCount;
    private volatile int tradeCount;
    private volatile int closedTradeCount;
    private volatile int winningTradeCount;
    private volatile int losingTradeCount;
    private volatile BigDecimal finalEquity;
    private volatile BigDecimal finalCash;
    private volatile BigDecimal finalBaseAmount;
    private volatile String error;
    @JsonIgnore
    private volatile List<BacktestBroker.BacktestTrade> trades = List.of();
    @JsonIgnore
    private volatile List<BacktestEquityPoint> equityCurve = List.of();
}
