package com.trade.trading.strategy;

import com.trade.trading.config.TradingProperties;

public record ConfiguredTradingStrategy<C extends StrategyConfig>(
        String id,
        String type,
        String bar,
        TradingStrategy<C> strategy,
        C config,
        TradingProperties.StrategyInstanceProperties source
) {
}
