package com.trade.trading.application.execution;

import com.trade.trading.domain.order.ExecutionEligibility;
import com.trade.trading.domain.order.PaperFillPolicy;
import com.trade.common.support.TradingMath;
import com.trade.trading.application.execution.OrderSizingService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.risk.RiskAssessment;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.application.port.TradingStateStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaperOrderExecutionService {
    private final OrderSizingService orderSizingService;
    private final TradingStateStore stateRepository;
    private final TradingProperties properties;
    private final RiskControlService riskControlService;

    public PaperOrderExecutionService(
            OrderSizingService orderSizingService,
            TradingStateStore stateRepository,
            TradingProperties properties,
            RiskControlService riskControlService
    ) {
        this.orderSizingService = orderSizingService;
        this.stateRepository = stateRepository;
        this.properties = properties;
        this.riskControlService = riskControlService;
    }

    public void execute(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        RiskAssessment riskAssessment = riskControlService.evaluate(decision, context);
        if (!riskAssessment.isAllowed()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(riskAssessment.skipReason());
            return;
        }

        if (decision == null || decision.isHold()) {
            decisionRecord.setExecutionStatus("HELD");
            return;
        }

        if (decision.getAction() == TradingAction.BUY) {
            paperBuy(decision, context, decisionRecord);
        } else if (decision.getAction() == TradingAction.SELL) {
            paperSell(decision, context, decisionRecord);
        } else if (decision.getAction() != null && decision.getAction().isDerivativeAction()) {
            paperDerivative(decision, context, decisionRecord);
        } else {
            decisionRecord.setExecutionStatus("HELD");
        }
    }

    private void paperBuy(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            record.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        OrderSizing sizing = orderSizingService.buySize(decision, context);
        if (!sizing.isExecutable()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }
        BigDecimal quoteAmount = new BigDecimal(sizing.getSize());
        BigDecimal price = lastPrice(context);
        if (price.signum() <= 0) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason("Paper BUY skipped: last price is unavailable");
            return;
        }
        PaperFillPolicy.BuyFill fill = PaperFillPolicy.buy(quoteAmount, price, properties.getTakerFeeRate());
        stateRepository.recordBuy(fill.netBase(), fill.averageCost());
        riskControlService.recordExecutedAction(decision, context);
        record.setOrderSize(sizing.getSize())
                .setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(fill.grossBase())
                .setAverageFillPrice(price)
                .setFee(fill.fee())
                .setFeeCcy(properties.getBaseCcy());
    }

    private void paperSell(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            record.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        OrderSizing sizing = orderSizingService.sellSize(decision, context);
        if (!sizing.isExecutable()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }
        BigDecimal baseAmount = new BigDecimal(sizing.getSize());
        BigDecimal price = lastPrice(context);
        BigDecimal feeQuote = PaperFillPolicy.sellFee(baseAmount, price, properties.getTakerFeeRate());
        stateRepository.recordSell(baseAmount);
        riskControlService.recordExecutedAction(decision, context);
        record.setOrderSize(sizing.getSize())
                .setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(baseAmount)
                .setAverageFillPrice(price)
                .setFee(feeQuote)
                .setFeeCcy(properties.getQuoteCcy());
    }

    private void paperDerivative(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            record.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        OrderSizing sizing = orderSizingService.derivativeSize(decision, context);
        if (!sizing.isExecutable()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }
        riskControlService.recordExecutedAction(decision, context);
        record.setOrderSize(sizing.getSize())
                .setExecutionStatus("PAPER_FILLED")
                .setAverageFillPrice(lastPrice(context));
    }

    private static BigDecimal lastPrice(TradingDecisionContext context) {
        return context == null || context.getTicker() == null
                ? BigDecimal.ZERO
                : TradingMath.decimal(context.getTicker().getLast());
    }
}
