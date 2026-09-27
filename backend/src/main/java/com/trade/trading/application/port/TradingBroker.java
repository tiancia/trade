package com.trade.trading.application.port;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;

public interface TradingBroker {
    void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord decisionRecord);
}
