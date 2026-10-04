package com.trade.trading.infrastructure.broker;

import com.trade.trading.application.port.TradingBroker;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;

/**
 * Selects the execution implementation for a strategy decision.
 *
 * <p>{@code trade.trading.execution-mode=live} sends orders to OKX through
 * {@link OkxLiveBroker}; {@code paper} uses {@link PaperBroker}.
 * BACKTEST is driven by the separate historical API and cannot execute here.</p>
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
        if (properties.getExecutionMode() == ExecutionMode.LIVE) {
            liveBroker.execute(decision, context, decisionRecord);
            return;
        }
        if (properties.getExecutionMode() == ExecutionMode.PAPER) {
            paperBroker.execute(decision, context, decisionRecord);
            return;
        }
        decisionRecord.setExecutionStatus("SKIPPED")
                .setSkipReason("Realtime execution requires PAPER or LIVE; use /api/trading/backtests for historical backtests");
    }
}
