package com.trade.trading.backtest;

import com.trade.trading.execution.BacktestBroker;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class BacktestRun {
    private String runId;
    private BacktestStatus status = BacktestStatus.QUEUED;
    private BacktestRequest request;
    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;
    private BigDecimal totalReturn;
    private BigDecimal maxDrawdown;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private int tradeCount;
    private BigDecimal finalEquity;
    private String error;
    private List<BacktestBroker.BacktestTrade> trades = new ArrayList<>();
}
