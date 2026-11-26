package com.trade.polymarket.application.service;

import com.trade.ai.port.AiResponseParseErrorSink;
import com.trade.ai.domain.model.AiResponseParseErrorRecord;
import com.trade.polymarket.application.decision.AiPolymarketDecisionParser;
import com.trade.polymarket.application.decision.AiPolymarketPromptBuilder;
import com.trade.polymarket.application.execution.PolymarketOrderExecutor;
import com.trade.polymarket.port.PolymarketDecisionAuditSink;
import com.trade.polymarket.port.PolymarketExecutionGuard;
import com.trade.polymarket.port.PolymarketOrderRunner;
import com.trade.polymarket.domain.model.AiPolymarketDecision;
import com.trade.polymarket.domain.model.PolymarketDecisionAuditRecord;
import com.trade.polymarket.domain.model.PolymarketDecisionContext;
import com.trade.polymarket.domain.model.PolymarketMarketSnapshot;
import com.trade.polymarket.domain.model.PolymarketOrderResult;
import com.trade.polymarket.domain.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.polymarket.infrastructure.broker.PolymarketGeoblockService;
import com.trade.polymarket.infrastructure.market.PolymarketMarketContextCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AiPolymarketServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"disable", "restart-module", "restart-execution", "stop-task"})
    void invalidatedAiCallAuditsSkipWithoutReservingOrExecuting(String transition) {
        var properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(true);
        var executor = mock(PolymarketOrderExecutor.class);
        var guard = mock(com.trade.polymarket.port.PolymarketExecutionGuard.class);
        var audit = new CapturingAuditSink();
        var service = new AiPolymarketService(prompt -> {
            switch (transition) {
                case "disable" -> properties.setEnabled(false);
                case "restart-module" -> { properties.setEnabled(false); properties.setEnabled(true); }
                case "restart-execution" -> { properties.getExecution().setEnabled(false); properties.getExecution().setEnabled(true); }
                case "stop-task" -> properties.invalidateDecisions();
                default -> throw new AssertionError(transition);
            }
            return """
                    {"action":"BUY","reason":"fixture","tokenId":"token-1","outcome":"Yes","limitPrice":0.42,
                     "maxSpendUsdc":5,"winProbability":0.6,"confidence":0.9,"estimatedEdge":0.18}
                    """;
        }, new FakeContextCollector(context()), new AiPolymarketPromptBuilder(),
                new AiPolymarketDecisionParser(), executor, properties, audit, AiResponseParseErrorSink.NOOP, guard);

        assertFalse(service.runDecision());
        assertEquals("SKIPPED", audit.record.getOrderResult().getStatus());
        assertEquals("BUY", audit.record.getAiDecision().getAction().name());
        assertNotNull(audit.record.getCompletedAt());
        verifyNoInteractions(guard, executor);
    }

    @Test
    void restartDuringReservationReleasesTheSkippedRunAfterAuditAndAdmitsAFreshBuy() {
        var properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(true);
        var guard = mock(PolymarketExecutionGuard.class);
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var audit = new CapturingAuditSink();
        when(guard.reserve(1L)).thenAnswer(call -> {
            properties.setEnabled(false);
            properties.setEnabled(true);
            return true;
        });
        doAnswer(call -> {
            assertEquals("SKIPPED", audit.record.getOrderResult().getStatus());
            return null;
        }).when(guard).release(1L);
        when(guard.reserve(2L)).thenReturn(true);
        when(runner.placeOrder(any())).thenReturn("simulated");
        var service = new AiPolymarketService(prompt -> """
                {"action":"BUY","reason":"fixture","tokenId":"token-1","outcome":"Yes","limitPrice":0.42,
                 "maxSpendUsdc":5,"winProbability":0.6,"confidence":0.9,"estimatedEdge":0.18}
                """, new FakeContextCollector(context()), new AiPolymarketPromptBuilder(), new AiPolymarketDecisionParser(),
                new PolymarketOrderExecutor(properties, runner, geoblock), properties, audit, AiResponseParseErrorSink.NOOP, guard);

        service.runDecision();
        verify(guard).release(1L);
        verifyNoInteractions(geoblock, runner);
        service.runDecision();
        assertEquals("PLACED", audit.record.getOrderResult().getStatus());
        verify(runner).placeOrder(any());
        verify(guard).release(2L);
    }

    @Test
    void liveAuditFailureStopsBeforeCollectingOrExecutingAndReleasesDecisionLock() {
        var properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(true);
        var collector = mock(PolymarketMarketContextCollector.class);
        var executor = mock(PolymarketOrderExecutor.class);
        var audit = mock(PolymarketDecisionAuditSink.class);
        when(audit.start(any())).thenThrow(new IllegalStateException("database unavailable"));
        var service = new AiPolymarketService(prompt -> "{}", collector, new AiPolymarketPromptBuilder(),
                new AiPolymarketDecisionParser(), executor, properties, audit);

        assertThrows(IllegalStateException.class, service::runDecision);
        verifyNoInteractions(collector, executor);
        verify(audit).save(argThat(record -> record.getError().contains("requires a persisted decision audit")));

        doReturn(1L).when(audit).start(any());
        when(collector.collect()).thenReturn(new PolymarketDecisionContext().setMarkets(List.of()));
        assertFalse(service.runDecision());
        verify(collector).collect();
        verifyNoInteractions(executor);
    }

    @Test
    void executionFailureIsAuditedAndPropagatedToScheduler() {
        var properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        var executor = mock(PolymarketOrderExecutor.class);
        when(executor.execute(any(), any(), any())).thenThrow(new IllegalStateException("order rejected"));
        var audit = new CapturingAuditSink();
        var service = new AiPolymarketService(prompt -> "{\"action\":\"HOLD\",\"reason\":\"fixture\"}",
                new FakeContextCollector(context()), new AiPolymarketPromptBuilder(),
                new AiPolymarketDecisionParser(), executor, properties, audit);

        assertThrows(IllegalStateException.class, service::runDecision);
        assertEquals("order rejected", audit.record.getError());
        assertNotNull(audit.record.getCompletedAt());
    }

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
        public PolymarketOrderResult execute(AiPolymarketDecision decision, PolymarketDecisionContext context,
                                             AiPolymarketProperties.DecisionRun run) {
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
