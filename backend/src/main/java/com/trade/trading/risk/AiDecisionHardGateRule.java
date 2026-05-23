package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.support.TradingMath;

import java.math.BigDecimal;
import java.util.Optional;

public class AiDecisionHardGateRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        AiTradingDecision decision = context.getDecision();
        if (decision == null || decision.getAction() == null || decision.getAction() == TradingAction.HOLD) {
            return Optional.empty();
        }

        TradingProperties properties = context.getProperties();
        TradingProperties.StrategyProperties strategy = properties == null ? null : properties.getStrategy();
        if (strategy == null) {
            return Optional.empty();
        }

        TradingAction action = decision.getAction();
        if (!"PASS".equalsIgnoreCase(decision.getObjectiveAlignment())) {
            return violation(action + " skipped: objectiveAlignment must be PASS for non-HOLD actions");
        }
        if (!hasText(decision.getStrategyThesis())) {
            return violation(action + " skipped: strategyThesis is required for non-HOLD actions");
        }
        if (!hasText(decision.getStrategyInvalidation())) {
            return violation(action + " skipped: strategyInvalidation is required for non-HOLD actions");
        }
        if (!hasText(decision.getStrategyHorizon())) {
            return violation(action + " skipped: strategyHorizon is required for non-HOLD actions");
        }
        if (!hasText(decision.getThesisChangeEvidence())) {
            return violation(action + " skipped: thesisChangeEvidence is required for non-HOLD actions");
        }
        if (below(decision.getWinProbability(), strategy.getMinWinProbability())) {
            return violation(action + " skipped: winProbability "
                    + plain(decision.getWinProbability()) + " is below strategy minimum "
                    + plain(strategy.getMinWinProbability()));
        }
        if (below(decision.getConfidence(), strategy.getMinConfidence())) {
            return violation(action + " skipped: confidence "
                    + plain(decision.getConfidence()) + " is below strategy minimum "
                    + plain(strategy.getMinConfidence()));
        }

        BigDecimal winConfidenceScore = decision.getWinProbability() == null || decision.getConfidence() == null
                ? null
                : decision.getWinProbability().multiply(decision.getConfidence());
        if (below(winConfidenceScore, strategy.getMinWinConfidenceScore())) {
            return violation(action + " skipped: winProbability * confidence "
                    + plain(winConfidenceScore) + " is below strategy minimum "
                    + plain(strategy.getMinWinConfidenceScore()));
        }
        if (below(decision.getRiskRewardRatio(), strategy.getMinRiskRewardRatio())) {
            return violation(action + " skipped: riskRewardRatio "
                    + plain(decision.getRiskRewardRatio()) + " is below strategy minimum "
                    + plain(strategy.getMinRiskRewardRatio()));
        }
        if (below(decision.getExpectedNetEdgePercent(), properties.getMinExpectedNetEdgePercent())) {
            return violation(action + " skipped: expectedNetEdgePercent "
                    + plain(decision.getExpectedNetEdgePercent()) + " is below configured minimum "
                    + plain(properties.getMinExpectedNetEdgePercent()));
        }
        return Optional.empty();
    }

    private static Optional<RiskViolation> violation(String reason) {
        return Optional.of(RiskViolation.of("AI_DECISION_HARD_GATE", reason));
    }

    private static boolean below(BigDecimal value, BigDecimal minimum) {
        return minimum != null
                && minimum.signum() > 0
                && (value == null || value.compareTo(minimum) < 0);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : TradingMath.plain(value);
    }
}
