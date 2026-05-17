package com.trade.trading.decision;

import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiTradingDecisionParserTest {
    private final AiTradingDecisionParser parser = new AiTradingDecisionParser();

    @Test
    void parsesBuyDecisionFromJsonFence() {
        AiTradingDecision decision = parser.parse("""
                ```json
                {"action":"BUY","reason":"momentum breakout","buyQuoteAmountUsdt":"75.5","winProbability":0.61,"confidence":0.72,"objectiveAlignment":"PASS","expectedNetEdgePercent":0.003,"riskRewardRatio":1.8,"thesisChangeEvidence":"breakout with volume"}
                ```
                """);

        assertEquals(TradingAction.BUY, decision.getAction());
        assertEquals("momentum breakout", decision.getReason());
        assertEquals(0, new BigDecimal("75.5").compareTo(decision.getBuyQuoteAmountUsdt()));
        assertEquals(0, new BigDecimal("0.61").compareTo(decision.getWinProbability()));
        assertEquals(0, new BigDecimal("0.72").compareTo(decision.getConfidence()));
        assertEquals("PASS", decision.getObjectiveAlignment());
        assertEquals(0, new BigDecimal("0.003").compareTo(decision.getExpectedNetEdgePercent()));
        assertEquals(0, new BigDecimal("1.8").compareTo(decision.getRiskRewardRatio()));
        assertEquals("breakout with volume", decision.getThesisChangeEvidence());
    }

    @Test
    void downgradesInvalidActionToHold() {
        AiTradingDecision decision = parser.parse("""
                {"action":"WAIT","reason":"not sure"}
                """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals(
                "Invalid AI decision: action must be BUY, HOLD, SELL, OPEN_LONG, CLOSE_LONG, OPEN_SHORT, or CLOSE_SHORT",
                decision.getReason()
        );
        assertNull(decision.getBuyQuoteAmountUsdt());
        assertNull(decision.getSellBaseAmountBtc());
    }

    @Test
    void parsesDerivativeDecisionAndStrategyState() {
        AiTradingDecision decision = parser.parse("""
                {
                  "action": "OPEN_SHORT",
                  "reason": "breakdown with risk cap",
                  "orderSize": "2",
                  "winProbability": 0.59,
                  "confidence": 0.71,
                  "objectiveAlignment": "PASS",
                  "expectedNetEdgePercent": 0.004,
                  "riskRewardRatio": 2.0,
                  "thesisChangeEvidence": "support broke on heavy volume",
                  "strategyBias": "SHORT",
                  "strategyThesis": "bearish continuation while below resistance",
                  "strategyInvalidation": "price reclaims resistance with volume",
                  "strategyHorizon": "1-3 days"
                }
                """);

        assertEquals(TradingAction.OPEN_SHORT, decision.getAction());
        assertEquals(0, new BigDecimal("2").compareTo(decision.getOrderSize()));
        assertEquals("PASS", decision.getObjectiveAlignment());
        assertEquals(0, new BigDecimal("0.004").compareTo(decision.getExpectedNetEdgePercent()));
        assertEquals(0, new BigDecimal("2.0").compareTo(decision.getRiskRewardRatio()));
        assertEquals("support broke on heavy volume", decision.getThesisChangeEvidence());
        assertEquals("SHORT", decision.getStrategyBias());
        assertEquals("bearish continuation while below resistance", decision.getStrategyThesis());
        assertEquals("price reclaims resistance with volume", decision.getStrategyInvalidation());
        assertEquals("1-3 days", decision.getStrategyHorizon());
    }

    @Test
    void derivativeActionRequiresPositiveOrderSize() {
        AiTradingDecision decision = parser.parse("""
                {"action":"CLOSE_LONG","reason":"reduce risk","orderSize":0,"winProbability":0.55,"confidence":0.8}
                """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals("Invalid AI decision: CLOSE_LONG requires positive orderSize", decision.getReason());
    }

    @Test
    void sellRequiresPositiveBaseAmount() {
        AiTradingDecision decision = parser.parse("""
                {"action":"SELL","reason":"risk off","sellBaseAmountBtc":0,"winProbability":0.55,"confidence":0.8}
                """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals("Invalid AI decision: SELL requires positive sellBaseAmountBtc", decision.getReason());
    }

    @Test
    void buyRequiresProbabilityFields() {
        AiTradingDecision decision = parser.parse("""
                {"action":"BUY","reason":"missing confidence","buyQuoteAmountUsdt":10,"winProbability":0.55}
        """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals("Invalid AI decision: confidence must be between 0 and 1 for non-HOLD actions", decision.getReason());
    }
}
