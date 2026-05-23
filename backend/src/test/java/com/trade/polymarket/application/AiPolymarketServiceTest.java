package com.trade.polymarket.application;

import com.trade.ai.persistence.AiResponseParseErrorRecord;
import com.trade.ai.persistence.AiResponseParseErrorSink;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.decision.AiPolymarketDecisionParser;
import com.trade.polymarket.decision.AiPolymarketPromptBuilder;
import com.trade.polymarket.execution.PolymarketOrderExecutor;
import com.trade.polymarket.market.PolymarketMarketContextCollector;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketDecisionAuditRecord;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOrderResult;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.persistence.PolymarketDecisionAuditSink;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPolymarketServiceTest {
    @Test
    void savesAiResponseConfidenceProbabilityAndExecutionResult() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(false);
        String aiResponse = """
                {
                  "action": "BUY",
                  "reason": "edge looks positive",
                  "marketId": "market-1",
                  "marketSlug": "test-market",
                  "marketQuestion": "Will it happen?",
                  "outcome": "Yes",
                  "tokenId": "token-1",
                  "limitPrice": 0.42,
                  "maxSpendUsdc": 5,
                  "winProbability": 0.55,
                  "confidence": 0.91,
                  "estimatedEdge": 0.13
                }
                """;
        CapturingAuditSink auditSink = new CapturingAuditSink();
        AiPolymarketService service = new AiPolymarketService(
                prompt -> aiResponse,
                new FakeContextCollector(context()),
                new AiPolymarketPromptBuilder(),
                new AiPolymarketDecisionParser(),
                new FakeOrderExecutor(PolymarketOrderResult.dryRun("dry-run-order")),
                properties,
                auditSink
        );

        boolean result = service.runDecision();

        assertTrue(result);
        PolymarketDecisionAuditRecord audit = auditSink.record;
        assertNotNull(audit);
        assertNotNull(audit.getDecisionId());
        assertNotNull(audit.getStartedAt());
        assertNotNull(audit.getCompletedAt());
        assertEquals(aiResponse, audit.getRawAiResponse());
        assertEquals("DRY_RUN", audit.getOrderResult().getStatus());
        assertEquals("BUY", audit.getAiDecision().getAction().name());
        assertDecimal("0.55", audit.getAiDecision().getWinProbability());
        assertDecimal("0.91", audit.getAiDecision().getConfidence());
        assertDecimal("0.55", audit.getAiDecision().getEstimatedProbability());
        assertDecimal("0.13", audit.getAiDecision().getEstimatedEdge());
        assertNotNull(audit.getPrompt());
    }

    @Test
    void invalidAiDecisionFallsBackToHoldAndRecordsParseError() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(false);
        String aiResponse = """
                {"action":"BUY","reason":"missing token","limitPrice":0.5,"maxSpendUsdc":5,"winProbability":0.6,"confidence":0.8,"estimatedEdge":0.1}
                """;
        CapturingAuditSink auditSink = new CapturingAuditSink();
        CapturingParseErrorSink parseErrorSink = new CapturingParseErrorSink();
        AiPolymarketService service = new AiPolymarketService(
                prompt -> aiResponse,
                new FakeContextCollector(context()),
                new AiPolymarketPromptBuilder(),
                new AiPolymarketDecisionParser(),
                new FakeOrderExecutor(PolymarketOrderResult.skipped("hold")),
                properties,
                auditSink,
                parseErrorSink
        );

        boolean result = service.runDecision();

        assertTrue(result);
        assertNotNull(parseErrorSink.record);
        assertEquals("POLYMARKET", parseErrorSink.record.getSource());
        assertEquals("DECISION_PAYLOAD", parseErrorSink.record.getPhase());
        assertEquals("1", parseErrorSink.record.getRelatedId());
        assertEquals(aiResponse, parseErrorSink.record.getRawResponse());
        assertEquals("HOLD", parseErrorSink.record.getFallbackAction());
        assertTrue(parseErrorSink.record.getErrorMessage().contains("BUY requires tokenId"));
        assertEquals("HOLD", auditSink.record.getAiDecision().getAction().name());
    }

    private static PolymarketDecisionContext context() {
        PolymarketOutcomeSnapshot outcome = new PolymarketOutcomeSnapshot()
                .setOutcome("Yes")
                .setTokenId("token-1")
                .setBestBid(new BigDecimal("0.40"))
                .setBestAsk(new BigDecimal("0.42"));
        PolymarketMarketSnapshot market = new PolymarketMarketSnapshot()
                .setId("market-1")
                .setSlug("test-market")
                .setQuestion("Will it happen?")
                .setAcceptingOrders(true)
                .setEnableOrderBook(true)
                .setOutcomes(List.of(outcome));
        return new PolymarketDecisionContext()
                .setAiParameters(Map.of("exchange", "Polymarket"))
                .setAiParametersJson("{\"exchange\":\"Polymarket\"}")
                .setMarkets(List.of(market));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static class FakeContextCollector extends PolymarketMarketContextCollector {
        private final PolymarketDecisionContext context;

        FakeContextCollector(PolymarketDecisionContext context) {
            super(null, new AiPolymarketProperties());
            this.context = context;
        }

        @Override
        public PolymarketDecisionContext collect() {
            return context;
        }
    }

    private static class FakeOrderExecutor extends PolymarketOrderExecutor {
        private final PolymarketOrderResult result;

        FakeOrderExecutor(PolymarketOrderResult result) {
            super(new AiPolymarketProperties(), null, null);
            this.result = result;
        }

        @Override
        public PolymarketOrderResult execute(AiPolymarketDecision decision, PolymarketDecisionContext context) {
            return result;
        }
    }

    private static class CapturingAuditSink implements PolymarketDecisionAuditSink {
        private PolymarketDecisionAuditRecord record;
        private long nextId = 1;

        @Override
        public Long start(PolymarketDecisionAuditRecord record) {
            record.setDecisionId(nextId++);
            return record.getDecisionId();
        }

        @Override
        public void save(PolymarketDecisionAuditRecord record) {
            this.record = record;
        }
    }

    private static class CapturingParseErrorSink implements AiResponseParseErrorSink {
        private AiResponseParseErrorRecord record;

        @Override
        public void save(AiResponseParseErrorRecord record) {
            this.record = record;
        }
    }
}
