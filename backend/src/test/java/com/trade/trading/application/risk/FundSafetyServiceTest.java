package com.trade.trading.application.risk;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.CancelAllAfterResp;
import com.trade.trading.port.FundSafetyRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
                properties, new SimpleMeterRegistry()), repository, okx);
    }

    private record HaltedFixture(FundSafetyService service, FundSafetyRepository repository, OkxApi okx) { }

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
                properties, new SimpleMeterRegistry());

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
                properties, new SimpleMeterRegistry());

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
                meterRegistry
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
                new SimpleMeterRegistry()
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
