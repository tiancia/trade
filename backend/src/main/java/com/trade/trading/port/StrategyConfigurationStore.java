package com.trade.trading.port;

import com.trade.trading.domain.model.StrategyConfigurationVersion;

import java.util.List;

/** Shared durable configuration history with optimistic revision checks. */
public interface StrategyConfigurationStore {
    StrategyConfigurationVersion currentOrCreate(StrategyConfigurationVersion baseline);

    StrategyConfigurationVersion update(long expectedVersion, StrategyConfigurationVersion next);

    <T> T withUnchangedVersions(java.util.Map<String, Long> versions, java.util.function.Supplier<T> action);

    List<StrategyConfigurationVersion> history(String strategyId, int offset, int limit);
}
