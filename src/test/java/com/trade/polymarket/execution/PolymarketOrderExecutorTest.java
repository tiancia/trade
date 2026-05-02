package com.trade.polymarket.execution;

import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketAction;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOrderRequest;
import com.trade.polymarket.model.PolymarketOrderResult;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketOrderExecutorTest {
    @Test
    void dryRunBuildsCappedBuyOrderWithoutCallingRunner() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMaxOrderUsdc(new BigDecimal("5"));
        properties.setMinOrderSize(new BigDecimal("1"));
        RecordingRunner runner = new RecordingRunner();
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, runner, null);

        PolymarketOrderResult result = executor.execute(buyDecision(), context());

        assertTrue(result.isDryRun());
        assertFalse(runner.called);
        assertTrue(result.getResponseBody().contains("side=BUY"));
        assertTrue(result.getResponseBody().contains("spendUsdc=5"));
    }

    @Test
    void skipsWhenEdgeIsTooSmall() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMinExpectedEdge(new BigDecimal("0.10"));
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, new RecordingRunner(), null);

        AiPolymarketDecision decision = buyDecision().setEstimatedEdge(new BigDecimal("0.04"));
        PolymarketOrderResult result = executor.execute(decision, context());

        assertEquals("SKIPPED", result.getStatus());
        assertEquals("estimatedEdge below configured minimum", result.getSkipReason());
    }

    private static AiPolymarketDecision buyDecision() {
        return new AiPolymarketDecision()
                .setAction(PolymarketAction.BUY)
                .setReason("edge")
                .setTokenId("token-1")
                .setOutcome("Yes")
                .setLimitPrice(new BigDecimal("0.50"))
                .setMaxSpendUsdc(new BigDecimal("8"))
                .setConfidence(new BigDecimal("0.75"))
                .setEstimatedProbability(new BigDecimal("0.60"))
                .setEstimatedEdge(new BigDecimal("0.10"));
    }

    private static PolymarketDecisionContext context() {
        PolymarketOutcomeSnapshot outcome = new PolymarketOutcomeSnapshot()
                .setOutcome("Yes")
                .setTokenId("token-1")
                .setMinOrderSize("1");
        PolymarketMarketSnapshot market = new PolymarketMarketSnapshot()
                .setSlug("market")
                .setQuestion("Question?")
                .setAcceptingOrders(true)
                .setEnableOrderBook(true)
                .setOutcomes(List.of(outcome));
        return new PolymarketDecisionContext().setMarkets(List.of(market));
    }

    private static class RecordingRunner implements PolymarketOrderRunner {
        private boolean called;

        @Override
        public String placeOrder(PolymarketOrderRequest request) {
            called = true;
            return "{}";
        }
    }
}
