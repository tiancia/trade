package com.trade.trading.execution;

import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;

public interface TradingBroker {
    void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord decisionRecord);
}
