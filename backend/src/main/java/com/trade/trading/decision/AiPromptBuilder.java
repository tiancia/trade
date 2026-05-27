package com.trade.trading.decision;

import com.trade.trading.config.TradingProperties;

/**
 * Legacy OKX AI prompt builder retained only for historical tests/data tools.
 * The runtime OKX trading path no longer uses prompts or AI decisions.
 */
public class AiPromptBuilder {
    private final TradingProperties properties;

    public AiPromptBuilder(TradingProperties properties) {
        this.properties = properties;
    }

    public AiPromptBuilder() {
        this(new TradingProperties());
    }

    public String buildPrompt(String aiParametersJson) {
        String actionSchema = actionSchema();
        String amountRules = amountRules();
        String productRules = productRules();

        return """
                You are an automated crypto trading decision engine.
                You must optimize for the persistent strategy objective, not for an isolated one-off trade.
                The long-term objective is a hard decision contract: preserve capital first, avoid churn second, and compound account equity only when a setup clears the configured gates.
                Use the provided market, account, position, order, fill, instrument-rule, local cost-basis, trading-cost, recent-decision, strategy-profile, decision-policy, and strategy-state data.
                Return exactly one JSON object. Do not include markdown, comments, or extra text.

                Required JSON schema:
                {
                  "action": "%s",
                  "reason": "short decision reason",
                  "buyQuoteAmountUsdt": 0,
                  "sellBaseAmountBtc": 0,
                  "orderSize": 0,
                  "winProbability": 0.0,
                  "confidence": 0.0,
                  "objectiveAlignment": "PASS|FAIL",
                  "expectedNetEdgePercent": 0.0,
                  "riskRewardRatio": 0.0,
                  "thesisChangeEvidence": "specific new evidence supporting a thesis change or action",
                  "strategyBias": "LONG|SHORT|NEUTRAL",
                  "strategyThesis": "current persistent trading thesis",
                  "strategyInvalidation": "specific condition that invalidates the thesis",
                  "strategyHorizon": "expected holding or review horizon"
                }

                Rules:
                - Allowed actions for this run are provided in allowedActions and must match the action field.
                %s
                - For HOLD, set all amount fields to 0 and set winProbability to 0 unless you are explicitly scoring an existing position.
                - winProbability is your estimated probability that the chosen action will be net profitable after fees, spread, and liquidation or margin risk over the stated strategyHorizon.
                - confidence is your self-assessed confidence in the final action, as a decimal between 0 and 1.
                - For any non-HOLD action, winProbability and confidence are required and must be between 0 and 1.
                - objectiveAlignment must be PASS only when the action advances strategyProfile.objective within strategyProfile.horizon without violating riskLimits; otherwise it must be FAIL and action must be HOLD.
                - expectedNetEdgePercent is the expected return edge after fees, spread, and slippage, before comparing it to the configured minExpectedNetEdgePercent hurdle. Use decimal ratio units, so 0.003 means 0.003 of notional.
                - riskRewardRatio is expected reward divided by defined downside risk over strategyHorizon. If you cannot define downside risk, set objectiveAlignment to FAIL and HOLD.
                - thesisChangeEvidence must name the concrete market/account evidence that justifies changing the persistent thesis or taking a non-HOLD action. If there is no materially new evidence, HOLD.
                - If data is insufficient, choose HOLD and explain why.
                - Treat strategyProfile.objective, strategyProfile.horizon, decisionPolicy, localTradingState.strategyState, and recentDecisionsNewestFirst as persistent memory.
                - HOLD is the default. For any non-HOLD action, all of these hard gates must pass:
                  objectiveAlignment = PASS;
                  winProbability >= strategyProfile.minWinProbability;
                  confidence >= strategyProfile.minConfidence;
                  winProbability * confidence >= strategyProfile.minWinConfidenceScore;
                  riskRewardRatio >= strategyProfile.minRiskRewardRatio;
                  expectedNetEdgePercent >= tradingCosts.minExpectedNetEdgePercent;
                  strategyThesis, strategyInvalidation, strategyHorizon, and thesisChangeEvidence are specific.
                - If any hard gate fails or is uncertain, choose HOLD. Do not ask the executor to decide later.
                - Do not reverse or add exposure unless the new evidence changes the strategy thesis or meets the thesis invalidation condition.
                - Prefer HOLD unless the expected move is greater than estimatedRoundTripTradingCostPercent plus minExpectedNetEdgePercent and aligns with the strategy objective.
                - Account for taker fees, spread, and repeated-decision churn. Do not trade just because price moved slightly.
                - Use localTradingState.averageCost and trackedPositionUnrealizedPnlAfterEstimatedSellFeePercent when deciding whether selling a tracked position is actually profitable after fees.
                - Use recentDecisionsNewestFirst to avoid repeating the same BUY/SELL without materially new market evidence.
                - The reason must briefly state which hard gates passed or which gate failed.
                %s

                AI input parameters:
                %s
                """.formatted(actionSchema, amountRules, productRules, aiParametersJson);
    }

    private String actionSchema() {
        if (properties.isSpotInstrument()) {
            return "BUY|HOLD|SELL";
        }
        if (properties.isShortEnabled()) {
            return "OPEN_LONG|CLOSE_LONG|OPEN_SHORT|CLOSE_SHORT|HOLD";
        }
        return "OPEN_LONG|CLOSE_LONG|HOLD";
    }

    private String amountRules() {
        if (properties.isSpotInstrument()) {
            return """
                - For BUY, set a positive buyQuoteAmountUsdt and omit or set sellBaseAmountBtc and orderSize to 0.
                - For SELL, set a positive sellBaseAmountBtc and omit or set buyQuoteAmountUsdt and orderSize to 0.
                - BUY size is quote currency and will be capped by maxBuyQuoteAmountUsdt and available quote balance.
                - SELL size is base currency and will be capped by available base balance and maxSellPositionRatio.
                """;
        }
        return """
                - For OPEN_LONG, CLOSE_LONG, OPEN_SHORT, or CLOSE_SHORT, set a positive orderSize and set spot amount fields to 0.
                - orderSize is the OKX sz value for this instrument and will be capped by maxDerivativeOrderSize and instrumentRules.
                - OPEN_SHORT is forbidden unless strategyProfile.allowShort is true.
                - CLOSE_LONG and CLOSE_SHORT must reduce existing exposure; do not use a close action to flip direction.
                """;
    }

    private String productRules() {
        if (properties.isSpotInstrument()) {
            return "- This account path is spot trading. Do not propose leverage, shorting, or derivative-only actions.";
        }
        return """
                - This account path may use derivatives. Use positions, positionMode, tdMode, instrumentRules.lotSz, instrumentRules.minSz, and instrumentRules.maxMktSz.
                - OPEN_LONG maps to buy; CLOSE_LONG maps to sell; OPEN_SHORT maps to sell; CLOSE_SHORT maps to buy.
                - In long_short position mode, close actions use the matching posSide. In net position mode, close actions use reduceOnly.
                - Consider leverage, liquidation distance, margin mode, current positions, and unrealized PnL before changing exposure.
                """;
    }
}
