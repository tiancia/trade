package com.trade.trading.infrastructure.broker;

import com.trade.trading.application.execution.PaperOrderExecutionService;
import com.trade.trading.application.port.TradingBroker;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import org.springframework.stereotype.Component;

/** Broker plug-in delegating to the execution use case. */
@Component
public class PaperBroker implements TradingBroker {
    private final PaperOrderExecutionService execution;
    public PaperBroker(PaperOrderExecutionService execution) { this.execution = execution; }
    @Override
    public void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord record) {
        execution.execute(decision, context, record);
    }
}
