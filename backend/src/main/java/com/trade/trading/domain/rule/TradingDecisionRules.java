package com.trade.trading.domain.rule;

import com.trade.trading.domain.model.AiTradingDecision;
import com.trade.trading.domain.model.TradingAction;
import java.math.BigDecimal;

/** Non-HOLD probability and positive-size invariants for legacy trading decisions. */
public final class TradingDecisionRules {
    public static AiTradingDecision validated(AiTradingDecision decision) {
        TradingAction action = decision.getAction();
        String rawResponse = decision.getRawResponse();
        if (action == TradingAction.BUY) {
            String validationError = validateProbabilityFields(decision);
            if (validationError != null) {
                return AiTradingDecision.hold(validationError, rawResponse);
            }
            BigDecimal amount = positive(decision.getBuyQuoteAmountUsdt());
            if (amount == null) {
                return AiTradingDecision.hold("Invalid AI decision: BUY requires positive buyQuoteAmountUsdt", rawResponse);
            }
            decision.setBuyQuoteAmountUsdt(amount);
        } else if (action == TradingAction.SELL) {
            String validationError = validateProbabilityFields(decision);
            if (validationError != null) {
                return AiTradingDecision.hold(validationError, rawResponse);
            }
            BigDecimal amount = positive(decision.getSellBaseAmountBtc());
            if (amount == null) {
                return AiTradingDecision.hold("Invalid AI decision: SELL requires positive sellBaseAmountBtc", rawResponse);
            }
            decision.setSellBaseAmountBtc(amount);
        } else if (action.isDerivativeAction()) {
            String validationError = validateProbabilityFields(decision);
            if (validationError != null) {
                return AiTradingDecision.hold(validationError, rawResponse);
            }
            BigDecimal amount = positive(decision.getOrderSize());
            if (amount == null) {
                return AiTradingDecision.hold("Invalid AI decision: " + action + " requires positive orderSize", rawResponse);
            }
            decision.setOrderSize(amount);
        }

        return decision;
    }
    private static String validateProbabilityFields(AiTradingDecision decision) {
        if (!isInUnitInterval(decision.getWinProbability())) {
            return "Invalid AI decision: winProbability must be between 0 and 1 for non-HOLD actions";
        }
        if (!isInUnitInterval(decision.getConfidence())) {
            return "Invalid AI decision: confidence must be between 0 and 1 for non-HOLD actions";
        }
        return null;
    }
    private static boolean isInUnitInterval(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }
    private static BigDecimal positive(BigDecimal value) { return value != null && value.signum() > 0 ? value : null; }
}
