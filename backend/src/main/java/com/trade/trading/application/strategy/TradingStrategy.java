package com.trade.trading.application.strategy;

import com.trade.trading.domain.model.StrategyDecision;

public interface TradingStrategy<C extends StrategyConfig> {
    String type();

    Class<C> configType();

    StrategyDecision evaluate(StrategyEvaluationContext context, C config);
}
