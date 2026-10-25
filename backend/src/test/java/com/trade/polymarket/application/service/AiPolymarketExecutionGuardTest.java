package com.trade.polymarket.application.service;

import com.trade.ai.application.port.AiResponseParseErrorSink;
import com.trade.polymarket.application.decision.AiPolymarketDecisionParser;
import com.trade.polymarket.application.decision.AiPolymarketPromptBuilder;
import com.trade.polymarket.application.execution.PolymarketOrderExecutor;
import com.trade.polymarket.application.port.PolymarketDecisionAuditSink;
import com.trade.polymarket.application.port.PolymarketExecutionGuard;
import com.trade.polymarket.domain.model.PolymarketDecisionAuditRecord;
import com.trade.polymarket.domain.model.PolymarketDecisionContext;
import com.trade.polymarket.domain.model.PolymarketMarketSnapshot;
import com.trade.polymarket.domain.model.PolymarketOrderResult;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.polymarket.infrastructure.market.PolymarketMarketContextCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPolymarketExecutionGuardTest {
    private static final String BUY = """
            {"action":"BUY","reason":"fixture","tokenId":"token-1","outcome":"Yes","limitPrice":0.42,
             "maxSpendUsdc":5,"winProbability":0.55,"confidence":0.91,"estimatedEdge":0.13}
            """;
    private static final String HOLD = "{\"action\":\"HOLD\",\"reason\":\"fixture\"}";

    @Test
    void existingPendingExecutionBlocksBeforeCallingTheOrderExecutor() {
        Fixture fixture = new Fixture(true, BUY);
        fixture.pending.set(99L);

        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);

        verify(fixture.guard).reserve(1L);
        verify(fixture.guard, never()).release(anyLong());
        verifyNoInteractions(fixture.executor);
        assertEquals(99L, fixture.pending.get());
        verify(fixture.audit).save(argThat(record -> record.getError() != null));
    }

    @Test
    void unavailableReservationStorageFailsClosedBeforeCallingTheOrderExecutor() {
        Fixture fixture = new Fixture(true, BUY);
        doThrow(new IllegalStateException("synthetic database outage")).when(fixture.guard).reserve(anyLong());

        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);

        verifyNoInteractions(fixture.executor);
        verify(fixture.guard, never()).release(anyLong());
    }

    @Test
    void uncertainAcknowledgementRetainsReservationAndBlocksAFreshServiceInstance() {
        Fixture fixture = new Fixture(true, BUY);
        doThrow(new IllegalStateException("synthetic acknowledgement missing"))
                .when(fixture.executor).execute(any(), any());

        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);
        assertEquals(1L, fixture.pending.get());
        // A fresh service must consult the same durable gate, rather than relying on its own Java lock.
        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);

        verify(fixture.guard).reserve(1L);
        verify(fixture.guard).reserve(2L);
        verify(fixture.guard, never()).release(anyLong());
        verify(fixture.executor, times(1)).execute(any(), any());
        assertEquals(1L, fixture.pending.get());
    }

    @Test
    void confirmedExecutionIsReleasedOnlyAfterItsAuditSaveCompletes() {
        Fixture fixture = new Fixture(true, BUY);

        assertTrue(fixture.newService().runDecision());

        assertEquals(List.of("reserve:1", "execute", "audit:1", "release:1"), fixture.events);
        assertNull(fixture.pending.get());
        assertTrue(fixture.newService().runDecision());
        verify(fixture.guard).release(2L);
        verify(fixture.executor, times(2)).execute(any(), any());
    }

    @Test
    void normalSkippedExecutionAlsoReleasesOnlyAfterItsAuditSaveCompletes() {
        Fixture fixture = new Fixture(true, BUY);
        fixture.result = PolymarketOrderResult.skipped("RISK_LIMIT");

        assertTrue(fixture.newService().runDecision());

        assertEquals(List.of("reserve:1", "execute", "audit:1", "release:1"), fixture.events);
        assertNull(fixture.pending.get());
    }

    @Test
    void lostFinalAuditRetainsReservationAndPreventsTheNextExecution() {
        Fixture fixture = new Fixture(true, BUY);
        doThrow(new IllegalStateException("synthetic audit outage")).when(fixture.audit).save(any());

        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);
        assertEquals(1L, fixture.pending.get());
        assertThrows(IllegalStateException.class, fixture.newService()::runDecision);

        verify(fixture.guard, never()).release(anyLong());
        verify(fixture.executor, times(1)).execute(any(), any());
        assertEquals(1L, fixture.pending.get());
    }

    @Test
    void holdAndDryRunBuyDoNotReserveOrReleaseLiveExecution() {
        Fixture hold = new Fixture(true, HOLD);
        hold.result = PolymarketOrderResult.skipped("HOLD");
        assertTrue(hold.newService().runDecision());
        verifyNoInteractions(hold.guard);

        Fixture dryRun = new Fixture(false, BUY);
        dryRun.result = PolymarketOrderResult.dryRun("fixture");
        assertTrue(dryRun.newService().runDecision());
        verifyNoInteractions(dryRun.guard);
    }

    @Test
    void legacyConstructorsWithoutTheDurableGuardCannotExecuteLiveBuys() {
        Fixture fixture = new Fixture(true, BUY);
        var sevenArguments = new AiPolymarketService(prompt -> BUY, fixture.collector,
                new AiPolymarketPromptBuilder(), new AiPolymarketDecisionParser(), fixture.executor,
                fixture.properties, fixture.audit);
        var eightArguments = new AiPolymarketService(prompt -> BUY, fixture.collector,
                new AiPolymarketPromptBuilder(), new AiPolymarketDecisionParser(), fixture.executor,
                fixture.properties, fixture.audit, AiResponseParseErrorSink.NOOP);

        assertThrows(IllegalStateException.class, sevenArguments::runDecision);
        assertThrows(IllegalStateException.class, eightArguments::runDecision);

        verifyNoInteractions(fixture.executor);
    }

    @Test
    void failedReservationReleaseStillUnlocksTheLocalDecisionLoop() throws Exception {
        Fixture fixture = new Fixture(true, BUY);
        var service = fixture.newService();
        doThrow(new IllegalStateException("synthetic release outage")).when(fixture.guard).release(anyLong());
        assertThrows(IllegalStateException.class, service::runDecision);

        // Use a different thread so an unreleased reentrant lock cannot make the test pass accidentally.
        try (var worker = Executors.newSingleThreadExecutor()) {
            var nextRun = worker.submit(service::runDecision);
            var failure = assertThrows(ExecutionException.class, () -> nextRun.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }

        verify(fixture.guard).reserve(2L);
        verify(fixture.executor, times(1)).execute(any(), any());
        assertEquals(1L, fixture.pending.get());
    }

    private static final class Fixture {
        final AiPolymarketProperties properties = new AiPolymarketProperties();
        final PolymarketMarketContextCollector collector = mock(PolymarketMarketContextCollector.class);
        final PolymarketOrderExecutor executor = mock(PolymarketOrderExecutor.class);
        final PolymarketDecisionAuditSink audit = mock(PolymarketDecisionAuditSink.class);
        final PolymarketExecutionGuard guard = mock(PolymarketExecutionGuard.class);
        final AtomicReference<Long> pending = new AtomicReference<>();
        final AtomicLong nextId = new AtomicLong(1);
        final List<String> events = new ArrayList<>();
        final String response;
        PolymarketOrderResult result = PolymarketOrderResult.placed("synthetic accepted order");

        Fixture(boolean live, String response) {
            this.response = response;
            properties.setEnabled(true);
            properties.getExecution().setEnabled(live);
            when(collector.collect()).thenReturn(new PolymarketDecisionContext()
                    .setAiParametersJson("{\"exchange\":\"Polymarket\"}")
                    .setMarkets(List.of(new PolymarketMarketSnapshot().setId("market-1"))));
            when(audit.start(any())).thenAnswer(invocation -> nextId.getAndIncrement());
            doAnswer(invocation -> {
                PolymarketDecisionAuditRecord record = invocation.getArgument(0);
                events.add("audit:" + record.getDecisionId());
                return null;
            }).when(audit).save(any());
            when(guard.reserve(anyLong())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                events.add("reserve:" + id);
                return pending.compareAndSet(null, id);
            });
            doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                assertEquals(id, pending.get(), "only the reservation owner may release it");
                events.add("release:" + id);
                pending.updateAndGet(current -> Objects.equals(current, id) ? null : current);
                return null;
            }).when(guard).release(anyLong());
            when(executor.execute(any(), any())).thenAnswer(invocation -> {
                events.add("execute");
                return result;
            });
        }

        AiPolymarketService newService() {
            return new AiPolymarketService(prompt -> response, collector, new AiPolymarketPromptBuilder(),
                    new AiPolymarketDecisionParser(), executor, properties, audit,
                    AiResponseParseErrorSink.NOOP, guard);
        }
    }
}
