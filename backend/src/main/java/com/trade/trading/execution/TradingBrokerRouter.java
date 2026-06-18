package com.trade.trading.execution;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import org.springframework.stereotype.Component;

/**
 * Selects the execution implementation for a strategy decision.
 *
 * <p>{@code trade.trading.execution-mode=live} sends orders to OKX through
 * {@link OkxLiveBroker}; every other mode uses {@link PaperBroker} so local
 * development and scheduled dry runs do not place real orders.</p>
 */
@Component
public class TradingBrokerRouter implements TradingBroker {
    private final TradingProperties properties;
    private final PaperBroker paperBroker;
    private final OkxLiveBroker liveBroker;

    public TradingBrokerRouter(
            TradingProperties properties,
            PaperBroker paperBroker,
            OkxLiveBroker liveBroker
    ) {
        this.properties = properties;
        this.paperBroker = paperBroker;
        this.liveBroker = liveBroker;
    }

    @Override
    public void execute(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        if (properties.getExecutionMode() == TradingProperties.ExecutionMode.LIVE) {
            liveBroker.execute(decision, context, decisionRecord);
            return;
        }
        paperBroker.execute(decision, context, decisionRecord);
    }
}
