package com.trade.trading.execution;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.OrderSizing;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskAssessment;
import com.trade.trading.risk.RiskControlService;
import com.trade.common.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PaperBroker implements TradingBroker {
    private final OrderSizingService orderSizingService;
    private final TradingStateRepository stateRepository;
    private final TradingProperties properties;
    private final RiskControlService riskControlService;

    public PaperBroker(
            OrderSizingService orderSizingService,
            TradingStateRepository stateRepository,
            TradingProperties properties,
            RiskControlService riskControlService
    ) {
        this.orderSizingService = orderSizingService;
        this.stateRepository = stateRepository;
        this.properties = properties;
        this.riskControlService = riskControlService;
    }

    @Override
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
        if (!properties.isSpotInstrument()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason("BUY skipped: use OPEN_LONG for non-spot instruments");
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
        BigDecimal grossBase = quoteAmount.divide(price, 18, RoundingMode.DOWN);
        BigDecimal feeBase = grossBase.multiply(properties.getTakerFeeRate());
        BigDecimal netBase = grossBase.subtract(feeBase);
        BigDecimal averageCost = quoteAmount.divide(netBase, 18, RoundingMode.HALF_UP);
        stateRepository.recordBuy(netBase, averageCost);
        riskControlService.recordExecutedAction(decision, context);
        record.setOrderSize(sizing.getSize())
                .setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(grossBase)
                .setAverageFillPrice(price)
                .setFee(feeBase.negate())
                .setFeeCcy(properties.getBaseCcy());
    }

    private void paperSell(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record
    ) {
        if (!properties.isSpotInstrument()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason("SELL skipped: use CLOSE_LONG or OPEN_SHORT for non-spot instruments");
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
        BigDecimal feeQuote = baseAmount.multiply(price).multiply(properties.getTakerFeeRate());
        stateRepository.recordSell(baseAmount);
        riskControlService.recordExecutedAction(decision, context);
        record.setOrderSize(sizing.getSize())
                .setExecutionStatus("PAPER_FILLED")
                .setFilledBaseAmount(baseAmount)
                .setAverageFillPrice(price)
                .setFee(feeQuote.negate())
                .setFeeCcy(properties.getQuoteCcy());
    }

    private void paperDerivative(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record
    ) {
        if (properties.isSpotInstrument()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason("Derivative action skipped: current instrument type is SPOT");
            return;
        }
        if ((decision.getAction() == TradingAction.OPEN_SHORT || decision.getAction() == TradingAction.CLOSE_SHORT)
                && !properties.isShortEnabled()) {
            record.setExecutionStatus("SKIPPED")
                    .setSkipReason(decision.getAction() + " skipped: short trading is disabled by strategy.allowShort");
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
