package com.trade.trading.strategy;

import com.trade.trading.model.StrategyDecision;

public interface TradingStrategy<C extends StrategyConfig> {
    String type();

    Class<C> configType();

    StrategyDecision evaluate(StrategyEvaluationContext context, C config);
}
