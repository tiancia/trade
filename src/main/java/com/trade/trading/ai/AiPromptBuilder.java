package com.trade.trading.ai;

import org.springframework.stereotype.Component;

@Component
public class AiPromptBuilder {

    public String buildPrompt(String aiParametersJson) {
        return """
                You are an automated crypto spot trading decision engine.
                Trade only BTC-USDT spot. You may choose BUY, HOLD, or SELL.
                Use the provided market, account, order, fill, instrument-rule, local cost-basis, trading-cost, and recent-decision data.
                Respect these hard limits: BUY size is quote USDT and will be capped by the system; SELL size is BTC and will be capped by available BTC.
                Return exactly one JSON object. Do not include markdown, comments, or extra text.

                Required JSON schema:
                {
                  "action": "BUY|HOLD|SELL",
                  "reason": "short decision reason",
                  "buyQuoteAmountUsdt": 0,
                  "sellBaseAmountBtc": 0
                }

                Rules:
                - For BUY, set a positive buyQuoteAmountUsdt and omit or set sellBaseAmountBtc to 0.
                - For SELL, set a positive sellBaseAmountBtc and omit or set buyQuoteAmountUsdt to 0.
                - For HOLD, omit both amounts or set both to 0.
                - If data is insufficient, choose HOLD and explain why.
                - This is a short-term spot strategy. Prefer HOLD unless the expected near-term move is greater than estimatedRoundTripTradingCostPercent plus minExpectedNetEdgePercent.
                - Account for taker fees, spread, and repeated-decision churn. Do not trade just because price moved slightly.
                - Use localTradingState.averageCost and trackedPositionUnrealizedPnlAfterEstimatedSellFeePercent when deciding whether selling a tracked position is actually profitable after fees.
                - Use recentDecisionsNewestFirst to avoid repeating the same BUY/SELL without materially new market evidence.

                AI input parameters:
                %s
                """.formatted(aiParametersJson);
    }
}
