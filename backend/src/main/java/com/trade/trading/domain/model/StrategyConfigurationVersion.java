package com.trade.trading.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable effective strategy parameters retained for decision attribution. */
public record StrategyConfigurationVersion(
        String strategyId, String type, String bar, long version,
        String changedAt, String reason, Map<String, Object> params
) {
    public StrategyConfigurationVersion {
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
