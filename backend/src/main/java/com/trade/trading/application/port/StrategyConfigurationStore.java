package com.trade.trading.application.port;

import com.trade.trading.domain.model.StrategyConfigurationVersion;

import java.util.List;

/** Shared durable configuration history with optimistic revision checks. */
public interface StrategyConfigurationStore {
    StrategyConfigurationVersion currentOrCreate(StrategyConfigurationVersion baseline);

    StrategyConfigurationVersion update(long expectedVersion, StrategyConfigurationVersion next);

    List<StrategyConfigurationVersion> history(String strategyId, int offset, int limit);
}
