package com.trade.trading.domain.model;

/** Immutable before/after evidence committed in the same transaction as the strategy change. */
public record TradingAiApplication(String executionMode, String instrument, String changedAt,
                                   String fromStrategyId, long fromRevision,
                                   StrategyConfigurationVersion fromConfiguration,
                                   String toStrategyId, long toRevision,
                                   StrategyConfigurationVersion toConfiguration) {}
