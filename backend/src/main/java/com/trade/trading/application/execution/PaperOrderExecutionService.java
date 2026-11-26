package com.trade.trading.application.execution;

import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.port.PaperAccountStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.order.OrderSizingRules;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.trade.trading.application.strategy.TradingStrategyMaintenance;
import java.math.BigDecimal;
import java.util.ConcurrentModificationException;

/** Funded spot PAPER execution. Cash, position, fees and the idempotency ledger commit together. */
@Component
public class PaperOrderExecutionService {
    private final OrderSizingService sizing;
    private final TradingProperties properties;
    private final RiskControlService risk;
    private final PaperAccountService accounts;
    private final TradingStrategyMaintenance maintenance;

    public PaperOrderExecutionService(OrderSizingService sizing, TradingProperties properties,
                                      RiskControlService risk, PaperAccountService accounts) {
        this(sizing, properties, risk, accounts, null);
    }

    @Autowired
    public PaperOrderExecutionService(OrderSizingService sizing, TradingProperties properties,
                                     RiskControlService risk, PaperAccountService accounts, TradingStrategyMaintenance maintenance) {
        this.sizing = sizing;
        this.properties = properties;
        this.risk = risk;
        this.accounts = accounts;
        this.maintenance = maintenance;
    }

    public void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord record) {
        if (decision == null || decision.isHold()) { record.setExecutionStatus("HELD"); return; }
        if (!properties.isSpotInstrument() || (decision.getAction() != TradingAction.BUY && decision.getAction() != TradingAction.SELL)) {
            skip(record, "Funded simulation currently supports spot BUY/SELL only"); return;
        }
        if (context == null || context.getPaperSessionId() == null) {
            skip(record, "Initialize simulated funds before trading"); return;
        }
        try {
            // Resolve exchange FX before taking the strategy maintenance lock.
            var facts = sizing.spotFacts(context);
            if (maintenance == null) executeGuarded(decision, context, record, facts);
            else maintenance.execute(record, () -> { executeGuarded(decision, context, record, facts); return null; });
        } catch (ConcurrentModificationException | IllegalArgumentException e) { skip(record, e.getMessage()); }
    }

    private void executeGuarded(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord record,
                                OrderSizingRules.SizingFacts facts) {
        var assessment = risk.evaluate(decision, context);
        if (!assessment.isAllowed()) { skip(record, assessment.skipReason()); return; }
        OrderSizing order = sizing.spotSize(decision, context, facts);
        if (!order.isExecutable()) { skip(record, order.getSkipReason()); return; }
        // Let a rejected fill leave the outer transaction before converting it
        // to a normal skip. Catching here would leave a rollback-only transaction
        // and surface an UnexpectedRollbackException on the shared fence commit.
        PaperAccountStore.AppliedFill applied = accounts.fill(context, record, decision.getAction().name(), new BigDecimal(order.getSize()));
        var fill = applied.fill();
        record.setOrderSize(fill.requestedSize().stripTrailingZeros().toPlainString()).setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(fill.filledBase()).setAverageFillPrice(fill.price())
                .setFee(fill.feeQuote().negate()).setFeeCcy(properties.getQuoteCcy()).setIdempotentReplay(applied.replay());
        // Executed-action and completed-trade risk facts commit in accounts.fill,
        // together with cash, position and the idempotent fill row.
    }
    private static void skip(TradingDecisionRecord record, String reason) {
        record.setExecutionStatus("SKIPPED").setSkipReason(reason);
    }
}
