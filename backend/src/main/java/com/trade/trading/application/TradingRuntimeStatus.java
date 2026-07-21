package com.trade.trading.application;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingDecisionRecord;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

@Data
@Accessors(chain = true)
public class TradingRuntimeStatus {
    private TradingProperties.ExecutionMode executionMode;
    private boolean liveEnabled;
    private List<String> runningStrategyIds;
    private String activeStrategyId;
    private long activeStrategyRevision;
    private Instant activeStrategyChangedAt;
    private TradingDecisionRecord lastDecision;
    private String lastError;
    private Instant lastRunStartedAt;
    private Instant lastRunCompletedAt;
    private boolean marketDataStale;
}
