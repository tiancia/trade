package com.trade.polymarket.decision;

import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiPolymarketDecisionParserTest {
    private final AiPolymarketDecisionParser parser = new AiPolymarketDecisionParser();

    @Test
    void parsesBuyDecision() {
        AiPolymarketDecision decision = parser.parse("""
                {
                  "action": "BUY",
                  "reason": "edge is large enough",
                  "marketId": "123",
                  "marketSlug": "test-market",
                  "marketQuestion": "Will it happen?",
                  "outcome": "Yes",
                  "tokenId": "456",
                  "limitPrice": 0.42,
                  "maxSpendUsdc": 5,
                  "confidence": 0.7,
                  "estimatedProbability": 0.5,
                  "estimatedEdge": 0.08
                }
                """);

        assertEquals(PolymarketAction.BUY, decision.getAction());
        assertEquals("456", decision.getTokenId());
        assertEquals(new BigDecimal("0.42"), decision.getLimitPrice());
        assertEquals(new BigDecimal("5"), decision.getMaxSpendUsdc());
    }

    @Test
    void invalidBuyFallsBackToHold() {
        AiPolymarketDecision decision = parser.parse("""
                {"action":"BUY","reason":"missing token","limitPrice":0.5,"maxSpendUsdc":5,"confidence":0.8,"estimatedProbability":0.6,"estimatedEdge":0.1}
                """);

        assertEquals(PolymarketAction.HOLD, decision.getAction());
        assertEquals("Invalid Polymarket AI decision: BUY requires tokenId", decision.getReason());
    }

    @Test
    void extractsJsonFromMarkdownFence() {
        AiPolymarketDecision decision = parser.parse("""
                ```json
                {"action":"HOLD","reason":"not enough edge","limitPrice":0,"maxSpendUsdc":0,"confidence":0,"estimatedProbability":0,"estimatedEdge":0}
                ```
                """);

        assertEquals(PolymarketAction.HOLD, decision.getAction());
        assertEquals("not enough edge", decision.getReason());
    }
}
