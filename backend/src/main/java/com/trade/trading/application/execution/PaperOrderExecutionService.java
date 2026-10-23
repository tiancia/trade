package com.trade.trading.application.execution;

import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.port.PaperAccountStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;

/** Funded spot PAPER execution. Cash, position, fees and the idempotency ledger commit together. */
@Component
public class PaperOrderExecutionService {
    private static final Logger log = LoggerFactory.getLogger(PaperOrderExecutionService.class);
    private final OrderSizingService sizing;
    private final TradingProperties properties;
    private final RiskControlService risk;
    private final PaperAccountService accounts;

    public PaperOrderExecutionService(OrderSizingService sizing, TradingProperties properties,
                                      RiskControlService risk, PaperAccountService accounts) {
        this.sizing = sizing;
        this.properties = properties;
        this.risk = risk;
        this.accounts = accounts;
    }

    public void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord record) {
        if (decision == null || decision.isHold()) { record.setExecutionStatus("HELD"); return; }
        if (!properties.isSpotInstrument() || (decision.getAction() != TradingAction.BUY && decision.getAction() != TradingAction.SELL)) {
            skip(record, "Funded simulation currently supports spot BUY/SELL only"); return;
        }
        if (context == null || context.getPaperSessionId() == null) {
            skip(record, "Initialize simulated funds before trading"); return;
        }
        var assessment = risk.evaluate(decision, context);
        if (!assessment.isAllowed()) { skip(record, assessment.skipReason()); return; }
        OrderSizing order = decision.getAction() == TradingAction.BUY
                ? sizing.buySize(decision, context) : sizing.sellSize(decision, context);
        if (!order.isExecutable()) { skip(record, order.getSkipReason()); return; }
        PaperAccountStore.AppliedFill applied;
        try {
            applied = accounts.fill(context, record, decision.getAction().name(), new BigDecimal(order.getSize()));
        } catch (IllegalArgumentException | IllegalStateException | ConcurrentModificationException exception) {
            skip(record, exception.getMessage()); return;
        }
        var fill = applied.fill();
        record.setOrderSize(fill.requestedSize().stripTrailingZeros().toPlainString()).setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(fill.filledBase()).setAverageFillPrice(fill.price())
                .setFee(fill.feeQuote().negate()).setFeeCcy(properties.getQuoteCcy()).setIdempotentReplay(applied.replay());
        if (!applied.replay()) {
            try { risk.recordExecutedAction(decision, context); }
            catch (RuntimeException exception) {
                // The monetary transaction already committed. A secondary risk-write failure must not turn it into a failed/unfilled order.
                var metadata = new LinkedHashMap<String, Object>();
                if (record.getMetadata() != null) metadata.putAll(record.getMetadata());
                metadata.put("paperRiskUpdateWarning", "Simulated fill committed; risk-state update failed: " + exception.getMessage());
                record.setMetadata(metadata);
                log.warn("Simulated fill committed but risk update failed: decisionId={}, sessionId={}, reason={}",
                        record.getDecisionId(), context.getPaperSessionId(), exception.getMessage(), exception);
            }
        }
    }
    private static void skip(TradingDecisionRecord record, String reason) {
        record.setExecutionStatus("SKIPPED").setSkipReason(reason);
    }
}
