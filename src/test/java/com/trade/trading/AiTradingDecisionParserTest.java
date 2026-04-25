package com.trade.trading;

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
                {"action":"BUY","reason":"momentum breakout","buyQuoteAmountUsdt":"75.5"}
                ```
                """);

        assertEquals(TradingAction.BUY, decision.getAction());
        assertEquals("momentum breakout", decision.getReason());
        assertEquals(0, new BigDecimal("75.5").compareTo(decision.getBuyQuoteAmountUsdt()));
    }

    @Test
    void downgradesInvalidActionToHold() {
        AiTradingDecision decision = parser.parse("""
                {"action":"WAIT","reason":"not sure"}
                """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals("Invalid AI decision: action must be BUY, HOLD, or SELL", decision.getReason());
        assertNull(decision.getBuyQuoteAmountUsdt());
        assertNull(decision.getSellBaseAmountBtc());
    }

    @Test
    void sellRequiresPositiveBaseAmount() {
        AiTradingDecision decision = parser.parse("""
                {"action":"SELL","reason":"risk off","sellBaseAmountBtc":0}
                """);

        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals("Invalid AI decision: SELL requires positive sellBaseAmountBtc", decision.getReason());
    }
}
