package com.trade.polymarket.domain.rule;

import com.trade.polymarket.domain.model.AiPolymarketDecision;
import java.math.BigDecimal;

/** Business validity of a parsed prediction-market decision. */
public final class PolymarketDecisionRules {
    public static String validateBuy(AiPolymarketDecision decision) {
        if (!hasText(decision.getTokenId())) {
            return "Invalid Polymarket AI decision: BUY requires tokenId";
        }
        if (!hasText(decision.getOutcome())) {
            return "Invalid Polymarket AI decision: BUY requires outcome";
        }
        if (!isPositive(decision.getLimitPrice())) {
            return "Invalid Polymarket AI decision: BUY requires positive limitPrice";
        }
        if (decision.getLimitPrice().compareTo(BigDecimal.ONE) > 0) {
            return "Invalid Polymarket AI decision: limitPrice must be <= 1";
        }
        if (!isPositive(decision.getMaxSpendUsdc())) {
            return "Invalid Polymarket AI decision: BUY requires positive maxSpendUsdc";
        }
        if (!isInUnitInterval(decision.getConfidence())) {
            return "Invalid Polymarket AI decision: confidence must be between 0 and 1";
        }
        if (!isInUnitInterval(decision.getWinProbability())) {
            return "Invalid Polymarket AI decision: winProbability must be between 0 and 1";
        }
        if (decision.getEstimatedEdge() == null) {
            return "Invalid Polymarket AI decision: estimatedEdge is required";
        }
        return null;
    }
    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
    private static boolean isInUnitInterval(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
