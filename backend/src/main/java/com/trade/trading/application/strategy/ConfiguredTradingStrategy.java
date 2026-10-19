package com.trade.trading.application.strategy;

import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.domain.model.StrategyConfigurationVersion;

public record ConfiguredTradingStrategy<C extends StrategyConfig>(
        String id,
        String type,
        String bar,
        TradingStrategy<C> strategy,
        C config,
        TradingProperties.StrategyInstanceProperties source,
        StrategyConfigurationVersion configuration
) {
}
