package com.trade.trading.application.risk;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.CancelAllAfterReq;
import com.trade.client.okx.dto.CancelAllAfterResp;
import com.trade.trading.port.FundSafetyRepository;
import com.trade.trading.application.LocalLiveAccountLock;
import com.trade.trading.port.TradingOrderRepository;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.domain.risk.FundSafetyStatus;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class FundSafetyServiceTest {
    @Test
    void enforcerThatReadAnOldHaltSkipsEveryExchangeActionAfterResume() {
        var f = haltedFixture();
        var halted = f.repository().getOrCreate("live");
        when(f.repository().getOrCreate("live")).thenReturn(halted,
                new FundSafetyState().setStatus(FundSafetyStatus.ACTIVE).setVersion(5));
        assertFalse(f.service().enforceIfHalted());
        verify(f.okx(), never()).cancelAllAfter(any());
        verify(f.okx(), never()).getPendingOrders(any());
        verify(f.okx(), never()).cancelOrder(any());
    }

    @Test
    void otherInstrumentsPendingOrdersOrUnverifiedPositionsBlockAccountResume() {
        var f = haltedFixture();
        OrderInfoResp pending = new OrderInfoResp();
        pending.setInstId("ETH-USDT");
        when(f.okx().getPendingOrders(argThat(request -> request != null && request.getInstId() == null
                && request.getInstType() == null))).thenReturn(OkxResponse.success(List.of(pending)));
        assertTrue(assertThrows(IllegalStateException.class, () -> f.service().resume(4, "checked", "RESUME_LIVE_TRADING"))
                .getMessage().contains("OKX"));
        when(f.okx().getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        when(f.orders().hasUnresolvedLiveOrders()).thenReturn(true, false);
        assertTrue(assertThrows(IllegalStateException.class, () -> f.service().resume(4, "checked", "RESUME_LIVE_TRADING"))
                .getMessage().contains("local orders"));
        when(f.repository().firstUnverifiedPosition(eq("live"), any(), any(), anyBoolean()))
                .thenReturn("ETH-USDT");
        assertTrue(assertThrows(IllegalStateException.class, () -> f.service().resume(4, "checked", "RESUME_LIVE_TRADING"))
                .getMessage().contains("ETH-USDT"));
        verify(f.okx(), never()).cancelAllAfter(any());
    }

    @Test
    void resumeWaitsForAnInFlightHaltActionBeforeDisarming() throws Exception {
        var f = haltedFixture();
        var arming = new CountDownLatch(1);
        var resumeStarted = new CountDownLatch(1);
        var finishArming = new CountDownLatch(1);
        var timeouts = new CopyOnWriteArrayList<String>();
        when(f.okx().cancelAllAfter(any())).thenAnswer(call -> {
            String timeout = call.getArgument(0, CancelAllAfterReq.class).getTimeOut();
            timeouts.add(timeout);
            if ("0".equals(timeout)) return timerResponse("0", "100");
            arming.countDown();
            assertTrue(finishArming.await(5, TimeUnit.SECONDS));
            return timerResponse("110", "100");
        });
        when(f.repository().resume(eq("live"), eq(4L), anyString(), any()))
                .thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.ACTIVE).setVersion(5));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var enforcer = workers.submit(f.service()::enforceIfHalted);
            try {
                assertTrue(arming.await(5, TimeUnit.SECONDS));
                var resume = workers.submit(() -> {
                    resumeStarted.countDown();
                    return f.service().resume(4, "checked", "RESUME_LIVE_TRADING");
                });
                assertTrue(resumeStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> resume.get(150, TimeUnit.MILLISECONDS));
                finishArming.countDown();
                assertTrue(enforcer.get(5, TimeUnit.SECONDS));
                assertEquals(FundSafetyStatus.ACTIVE, resume.get(5, TimeUnit.SECONDS).getStatus());
                assertEquals(List.of("10", "0"), timeouts);
            } finally {
                finishArming.countDown();
            }
        }
    }

    @Test
    void missingHaltTimestampCannotProveThatReconciliationIsFresh() {
        var f = haltedFixture();
        when(f.repository().getOrCreate("live")).thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.HALTED).setVersion(4));
        assertThrows(IllegalStateException.class, () -> f.service().resume(4, "checked", "RESUME_LIVE_TRADING"));
        verify(f.okx(), never()).cancelAllAfter(any());
    }

    @Test
    void aNewHaltDuringPendingOrderChecksPreventsTimerDisarm() {
        var fixture = haltedFixture();
        when(fixture.okx().getPendingOrders(any())).thenAnswer(call -> {
            when(fixture.repository().getOrCreate("live")).thenReturn(new FundSafetyState()
                    .setStatus(FundSafetyStatus.HALTED).setVersion(5).setHaltedAt(Instant.now()));
            return OkxResponse.success(List.of());
        });

        assertThrows(ConcurrentModificationException.class,
                () -> fixture.service().resume(4, "checked", "RESUME_LIVE_TRADING"));
        verify(fixture.okx(), never()).cancelAllAfter(any());
        verify(fixture.repository(), never()).resume(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void failedResumeRestoresTheTimerOnlyWhileTheAccountRemainsHalted() {
        for (var winningState : List.of(FundSafetyStatus.HALTED, FundSafetyStatus.ACTIVE)) {
            var fixture = haltedFixture();
            var timeouts = new ArrayList<String>();
            when(fixture.okx().cancelAllAfter(any())).thenAnswer(call -> {
                String timeout = call.getArgument(0, CancelAllAfterReq.class).getTimeOut();
                timeouts.add(timeout);
                return timerResponse("0".equals(timeout) ? "0" : "110", "100");
            });
            when(fixture.repository().resume(eq("live"), eq(4L), anyString(), any())).thenAnswer(call -> {
                when(fixture.repository().getOrCreate("live")).thenReturn(new FundSafetyState()
                        .setStatus(winningState).setVersion(5));
                throw new ConcurrentModificationException("competing operation won");
            });

            assertThrows(ConcurrentModificationException.class,
                    () -> fixture.service().resume(4, "checked", "RESUME_LIVE_TRADING"));
            assertEquals(winningState == FundSafetyStatus.HALTED ? List.of("0", "10") : List.of("0"), timeouts);
            verify(fixture.okx(), never()).cancelOrder(any());
        }
    }

    @Test
    void unknownDisarmResultRearmsTheTimerAndKeepsLocalSubmissionsBlocked() {
        var fixture = haltedFixture();
        when(fixture.okx().cancelAllAfter(any())).thenThrow(new IllegalStateException("missing disarm response"))
                .thenReturn(timerResponse("110", "100"));
        var error = assertThrows(IllegalStateException.class,
                () -> fixture.service().resume(4, "checked", "RESUME_LIVE_TRADING"));
        assertEquals("missing disarm response", error.getMessage());
        verify(fixture.okx()).cancelAllAfter(argThat(request -> "10".equals(request.getTimeOut())));
        verify(fixture.repository(), never()).resume(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void reconciliationFailureDuringDisarmBlocksResumeAndRestoresTheTimer() {
        var fixture = haltedFixture();
        when(fixture.okx().cancelAllAfter(any())).thenAnswer(call -> {
            when(fixture.state().getState()).thenReturn(new TradingState().setRiskState(
                    new TradingRiskState().setLastReconciliationError("position check failed")));
            return timerResponse("0", "100");
        }).thenReturn(timerResponse("110", "100"));
        assertThrows(IllegalStateException.class,
                () -> fixture.service().resume(4, "checked", "RESUME_LIVE_TRADING"));
        verify(fixture.okx()).cancelAllAfter(argThat(request -> "10".equals(request.getTimeOut())));
        verify(fixture.repository(), never()).resume(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void emptyTimerAcknowledgementCannotAuthorizeLiveResume() {
        var fixture = haltedFixture();
        when(fixture.okx().cancelAllAfter(any())).thenReturn(OkxResponse.success(List.of()));

        assertThrows(IllegalStateException.class,
                () -> fixture.service().resume(4, "operator checked", "RESUME_LIVE_TRADING"));

        verify(fixture.repository(), never()).resume(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void emptyCancellationAcknowledgementIsRecordedAsUnconfirmed() {
        var fixture = haltedFixture();
        OrderInfoResp pending = new OrderInfoResp();
        pending.setOrdId("pending");
        when(fixture.okx().getPendingOrders(any())).thenReturn(OkxResponse.success(List.of(pending)));
        when(fixture.okx().cancelAllAfter(any())).thenReturn(OkxResponse.success(List.of()));
        when(fixture.okx().cancelOrder(any())).thenReturn(OkxResponse.success(List.of()));

        fixture.service().enforceIfHalted();

        verify(fixture.repository()).recordActionError(eq("live"), argThat(error ->
                error != null && error.contains("cancel order pending")));
    }

    @Test
    void malformedOrStillArmedTimerAcknowledgementsKeepFundsHalted() {
        List<OkxResponse<CancelAllAfterResp>> responses = List.of(
                OkxResponse.fail("50000", "failed"),
                OkxResponse.success(null),
                OkxResponse.success(java.util.Arrays.asList((CancelAllAfterResp) null)),
                OkxResponse.success(List.of(timer("0", "100"), timer("0", "100"))),
                timerResponse(null, "100"), timerResponse("invalid", "100"),
                timerResponse("0", null), timerResponse("0", "0"), timerResponse("0", "-1"),
                timerResponse("110", "100"), timerResponse("-1", "100"));
        for (var response : responses) {
            var fixture = haltedFixture();
            when(fixture.okx().cancelAllAfter(any())).thenReturn(response);
            assertThrows(IllegalStateException.class,
                    () -> fixture.service().resume(4, "operator checked", "RESUME_LIVE_TRADING"));
            verify(fixture.repository(), never()).resume(anyString(), anyLong(), anyString(), any());
        }
    }

    @Test
    void timerNotArmedStillAttemptsEveryCancellationAndReportsTheFailure() {
        for (String trigger : new String[] {"0", "100", "99", null}) {
            var fixture = haltedFixture();
            when(fixture.okx().cancelAllAfter(any())).thenReturn(timerResponse(trigger, "100"));
            OrderInfoResp pending = new OrderInfoResp();
            pending.setOrdId("pending");
            when(fixture.okx().getPendingOrders(any())).thenReturn(OkxResponse.success(List.of(pending)));
            when(fixture.okx().cancelOrder(any())).thenReturn(OkxResponse.success(List.of(cancellation("pending", "0"))));

            assertTrue(fixture.service().enforceIfHalted());

            verify(fixture.okx()).cancelOrder(any());
            verify(fixture.repository()).recordActionError(eq("live"), argThat(error ->
                    error != null && error.contains("arm cancel-all-after") && !error.contains("cancel order")));
        }
    }

    @Test
    void cancellationRequiresSuccessCodeAndMatchingIdentity() {
        for (var acknowledgement : List.of(cancellation("pending", null), cancellation("other", "0"),
                cancellation(null, "0"))) {
            var fixture = haltedFixture();
            when(fixture.okx().cancelAllAfter(any())).thenReturn(timerResponse("110", "100"));
            OrderInfoResp pending = new OrderInfoResp();
            pending.setOrdId("pending");
            when(fixture.okx().getPendingOrders(any())).thenReturn(OkxResponse.success(List.of(pending)));
            when(fixture.okx().cancelOrder(any())).thenReturn(OkxResponse.success(List.of(acknowledgement)));

            fixture.service().enforceIfHalted();

            verify(fixture.repository()).recordActionError(eq("live"), argThat(error ->
                    error != null && error.contains("cancel order pending")));
        }
    }

    @Test
    void matchingCancellationAndArmedTimerClearExchangeActionError() {
        var fixture = haltedFixture();
        when(fixture.okx().cancelAllAfter(any())).thenReturn(timerResponse("110", "100"));
        OrderInfoResp pending = new OrderInfoResp();
        pending.setOrdId("pending");
        when(fixture.okx().getPendingOrders(any())).thenReturn(OkxResponse.success(List.of(pending)));
        when(fixture.okx().cancelOrder(any())).thenReturn(OkxResponse.success(List.of(cancellation("pending", "0"))));

        assertTrue(fixture.service().enforceIfHalted());

        verify(fixture.repository()).recordActionError("live", null);
        assertThrows(FundSafetyService.TradingFundsHaltedException.class, fixture.service()::requireActive);
    }

    private static OrderActionResp cancellation(String orderId, String code) {
        OrderActionResp response = new OrderActionResp();
        response.setOrdId(orderId);
        response.setSCode(code);
        return response;
    }

    private static CancelAllAfterResp timer(String trigger, String receivedAt) {
        CancelAllAfterResp response = new CancelAllAfterResp();
        response.setTriggerTime(trigger);
        response.setTs(receivedAt);
        return response;
    }

    private static OkxResponse<CancelAllAfterResp> timerResponse(String trigger, String receivedAt) {
        return OkxResponse.success(List.of(timer(trigger, receivedAt)));
    }

    private static HaltedFixture haltedFixture() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orders = mock(TradingOrderRepository.class);
        TradingStateRepository state = mock(TradingStateRepository.class);
        OkxApi okx = mock(OkxApi.class);
        Instant now = Instant.now();
        when(repository.getOrCreate("live")).thenReturn(new FundSafetyState()
                .setStatus(FundSafetyStatus.HALTED).setVersion(4).setHaltedAt(now.minusSeconds(60)));
        when(okx.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        when(orders.findReconciliationCandidates(anyString(), anyInt())).thenReturn(List.of());
        when(state.getState()).thenReturn(new TradingState().setPositionLastReconciledAt(now.toString())
                .setRiskState(new TradingRiskState().setLastReconciliationAt(now.toString())));
        return new HaltedFixture(new FundSafetyService(repository, orders, state, okx,
                properties, new SimpleMeterRegistry(), new LocalLiveAccountLock()), repository, state, okx, orders);
    }

    private record HaltedFixture(FundSafetyService service, FundSafetyRepository repository,
                                 TradingStateRepository state, OkxApi okx, TradingOrderRepository orders) { }

    @Test
    void missingPendingOrderDataCannotAuthorizeLiveResume() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orders = mock(TradingOrderRepository.class);
        TradingStateRepository state = mock(TradingStateRepository.class);
        OkxApi okx = mock(OkxApi.class);
        Instant reconciledAt = Instant.now();
        when(repository.getOrCreate("live")).thenReturn(new FundSafetyState()
                .setStatus(FundSafetyStatus.HALTED).setVersion(4)
                .setHaltedAt(reconciledAt.minusSeconds(60)));
        when(okx.getPendingOrders(any())).thenReturn(OkxResponse.success(null));
        when(orders.findReconciliationCandidates(anyString(), anyInt())).thenReturn(List.of());
        when(state.getState()).thenReturn(new TradingState()
                .setPositionLastReconciledAt(reconciledAt.toString())
                .setRiskState(new TradingRiskState().setLastReconciliationAt(reconciledAt.toString())));
        when(okx.cancelAllAfter(any())).thenReturn(OkxResponse.success(List.of()));
        FundSafetyService service = new FundSafetyService(repository, orders, state, okx,
                properties, new SimpleMeterRegistry(), new LocalLiveAccountLock());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resume(4, "operator checked", "RESUME_LIVE_TRADING"));

        assertTrue(error.getMessage().contains("no data"));
        verify(repository, never()).resume(anyString(), anyLong(), anyString(), any());
        verify(okx, never()).cancelAllAfter(any());
    }

    @Test
    void emergencyCancellationContinuesAfterTimerAndIndividualOrderFailures() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orders = mock(TradingOrderRepository.class);
        TradingStateRepository state = mock(TradingStateRepository.class);
        OkxApi okx = mock(OkxApi.class);
        FundSafetyState halted = new FundSafetyState().setStatus(FundSafetyStatus.HALTED);
        when(repository.halt(anyString(), anyString(), anyString(), any())).thenReturn(halted);
        when(repository.getOrCreate("live")).thenReturn(halted);
        when(okx.cancelAllAfter(any())).thenThrow(new IllegalStateException("timer unavailable"));
        OrderInfoResp first = new OrderInfoResp();
        first.setOrdId("first");
        OrderInfoResp second = new OrderInfoResp();
        second.setOrdId("second");
        when(okx.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of(first, second)));
        OrderActionResp rejected = new OrderActionResp();
        rejected.setSCode("51000");
        rejected.setSMsg("cancel failed");
        when(okx.cancelOrder(any(CancelOrderReq.class))).thenReturn(
                OkxResponse.success(List.of(rejected)), OkxResponse.success(List.of()));
        FundSafetyService service = new FundSafetyService(repository, orders, state, okx,
                properties, new SimpleMeterRegistry(), new LocalLiveAccountLock());

        service.halt("operator-api", "stop");

        verify(okx).cancelOrder(argThat(order -> "first".equals(order.getOrdId())));
        verify(okx).cancelOrder(argThat(order -> "second".equals(order.getOrdId())));
        verify(repository).recordActionError(eq("live"), argThat(error ->
                error.contains("timer unavailable") && error.contains("first") && error.contains("cancel failed")));
        assertThrows(FundSafetyService.TradingFundsHaltedException.class, service::requireActive);
    }

    @Test
    void haltIsPersistedBeforeExchangeCancellationAndBlocksNewOrders() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        // Closing the new-order gate must not disable emergency cancellation.
        properties.setLiveEnabled(false);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orderRepository = mock(TradingOrderRepository.class);
        TradingStateRepository stateRepository = mock(TradingStateRepository.class);
        OkxApi okxApi = mock(OkxApi.class);
        FundSafetyState halted = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.HALTED)
                .setReason("operator stop")
                .setVersion(1)
                .setUpdatedAt(Instant.now());
        when(repository.halt(anyString(), anyString(), anyString(), any())).thenReturn(halted);
        when(repository.getOrCreate("live")).thenReturn(halted);
        when(repository.recordActionError("live", null)).thenReturn(halted);
        when(okxApi.cancelAllAfter(any())).thenReturn(timerResponse("110", "100"));
        when(okxApi.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FundSafetyService service = new FundSafetyService(
                repository,
                orderRepository,
                stateRepository,
                okxApi,
                properties,
                meterRegistry, new LocalLiveAccountLock()
        );

        service.halt("operator-api", "operator stop");

        InOrder ordered = inOrder(repository, okxApi);
        ordered.verify(repository).halt(anyString(), anyString(), anyString(), any());
        ordered.verify(okxApi).cancelAllAfter(any());
        assertThrows(FundSafetyService.TradingFundsHaltedException.class, service::requireActive);
        assertEquals(1.0, meterRegistry.get("trade.trading.fund_safety.status").gauge().value());
        verify(okxApi).getPendingOrders(any());
    }

    @Test
    void liveResumeRequiresASuccessfulReconciliationAfterTheStop() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.setLiveEnabled(false);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orderRepository = mock(TradingOrderRepository.class);
        TradingStateRepository stateRepository = mock(TradingStateRepository.class);
        OkxApi okxApi = mock(OkxApi.class);
        Instant haltedAt = Instant.now().minusSeconds(60);
        FundSafetyState halted = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.HALTED)
                .setHaltedAt(haltedAt)
                .setVersion(4);
        FundSafetyState active = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.ACTIVE)
                .setVersion(5);
        when(repository.getOrCreate("live")).thenReturn(halted);
        when(repository.resume(eq("live"), eq(4L), eq("operator checked"), any())).thenReturn(active);
        when(orderRepository.findReconciliationCandidates(anyString(), anyInt()))
                .thenReturn(List.of());
        when(okxApi.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        when(okxApi.cancelAllAfter(any())).thenReturn(timerResponse("0", "100"));
        when(stateRepository.getState()).thenReturn(new TradingState()
                .setRiskState(new TradingRiskState()));
        FundSafetyService service = new FundSafetyService(
                repository,
                orderRepository,
                stateRepository,
                okxApi,
                properties,
                new SimpleMeterRegistry(), new LocalLiveAccountLock()
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.resume(4, "operator checked", "RESUME_LIVE_TRADING")
        );

        Instant reconciledAt = Instant.now();
        when(stateRepository.getState()).thenReturn(new TradingState()
                .setPositionLastReconciledAt(reconciledAt.toString())
                .setRiskState(new TradingRiskState()
                        .setLastReconciliationAt(reconciledAt.toString())
                        .setConsecutiveReconciliationFailures(0)));

        assertEquals(
                FundSafetyStatus.ACTIVE,
                service.resume(4, "operator checked", "RESUME_LIVE_TRADING").getStatus()
        );
        verify(okxApi).cancelAllAfter(any());
    }
}
