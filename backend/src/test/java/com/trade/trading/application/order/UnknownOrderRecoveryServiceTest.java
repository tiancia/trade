package com.trade.trading.application.order;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.trading.port.TradingOrderRepository;
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.domain.risk.FundSafetyStatus;
import com.trade.trading.infrastructure.config.TradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnknownOrderRecoveryServiceTest {
    @Test
    void operatorRecoveryPersistsTerminalAuditAndRequiresFreshReconciliationWithoutResumingOrSubmitting() {
        Fixture f = fixture();

        TradingOrder abandoned = recover(f);

        assertEquals(OrderStatus.ABANDONED, abandoned.getStatus());
        assertTrue(abandoned.getStatus().isTerminal());
        assertEquals("key", abandoned.getIdempotencyKey());
        assertEquals("client", abandoned.getClientOrderId());
        assertEquals(4, abandoned.getVersion());
        assertTrue(abandoned.getFailureMessage().contains("exchange-support-ticket-123"));
        assertEquals("OPERATOR_CONFIRMED_NOT_ACCEPTED", abandoned.getFailureCode());
        verify(f.orders).compareAndSet(eq(f.order), eq(abandoned), contains("operator-7"));
        var sequence = inOrder(f.state, f.orders);
        sequence.verify(f.state).recordReconciliationFailure(any(), contains("fresh full reconciliation"));
        sequence.verify(f.orders).compareAndSet(any(), any(), anyString());
        verify(f.safety, never()).resume(anyLong(), anyString(), anyString());
        verify(f.okx, never()).placeOrder(any());
        assertEquals(1, f.meters.get("trade.trading.orders.manual_recovery").counter().count());
    }

    @Test
    void emptyOrFailedLookupCannotAuthorizeAbandonment() {
        for (var response : List.of(OkxResponse.<OrderInfoResp>success(List.of()),
                OkxResponse.<OrderInfoResp>fail("50113", "invalid signature"))) {
            Fixture f = fixture();
            when(f.okx.getOrder(any())).thenReturn(response);
            assertThrows(IllegalStateException.class, () -> recover(f));
            verify(f.orders, never()).compareAndSet(any(), any(), any());
        }
    }

    @Test
    void foundOrderMustBeReconciledEvenWhenTerminal() {
        Fixture f = fixture();
        var found = new OrderInfoResp();
        found.setClOrdId("client");
        found.setState("filled");
        when(f.okx.getOrder(any())).thenReturn(OkxResponse.success(List.of(found)));
        assertThrows(IllegalStateException.class, () -> recover(f));
        verify(f.orders, never()).compareAndSet(any(), any(), any());
    }

    @Test
    void absenceOfPendingOrdersMustBeSuccessfullyVerified() {
        for (var response : List.of(OkxResponse.<OrderInfoResp>success(List.of(new OrderInfoResp())),
                OkxResponse.<OrderInfoResp>fail("50004", "timeout"),
                OkxResponse.<OrderInfoResp>success((List<OrderInfoResp>) null))) {
            Fixture f = fixture();
            when(f.okx.getPendingOrders(any())).thenReturn(response);
            assertThrows(IllegalStateException.class, () -> recover(f));
            verify(f.orders, never()).compareAndSet(any(), any(), any());
        }
    }

    @Test
    void activeOrRevisedFundStateCannotBeRecovered() {
        Fixture f = fixture();
        when(f.safety.state()).thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.ACTIVE).setVersion(8));
        assertThrows(IllegalStateException.class, () -> recover(f));
        verify(f.okx, never()).getOrder(any());
        when(f.safety.state()).thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.HALTED).setVersion(9));
        assertThrows(ConcurrentModificationException.class, () -> recover(f));
    }

    @Test
    void fundStateIsRecheckedAfterExchangeQueries() {
        Fixture f = fixture();
        when(f.safety.state()).thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.HALTED).setVersion(8),
                new FundSafetyState().setStatus(FundSafetyStatus.HALTED).setVersion(9));
        assertThrows(ConcurrentModificationException.class, () -> recover(f));
        verify(f.orders, never()).compareAndSet(any(), any(), any());
    }

    @Test
    void recentUnknownOrInFlightSubmitCannotBeAbandoned() {
        Fixture f = fixture();
        for (var snapshot : List.of(f.order.snapshot().toBuilder().status(OrderStatus.SUBMITTING).build(),
                f.order.snapshot().toBuilder().updatedAt(Instant.now()).build())) {
            when(f.orders.findByIdempotencyKey("key")).thenReturn(Optional.of(TradingOrder.restore(snapshot)));
            assertThrows(IllegalStateException.class, () -> recover(f));
        }
        verify(f.okx, never()).getOrder(any());
    }

    @Test
    void exchangeIdentityFillOrWrongInstrumentCannotBeDiscarded() {
        Fixture f = fixture();
        for (var snapshot : List.of(f.order.snapshot().toBuilder().exchangeOrderId("exchange").build(),
                f.order.snapshot().toBuilder().filledBaseAmount(BigDecimal.ONE).build(),
                f.order.snapshot().toBuilder().instId("ETH-USDT").build())) {
            when(f.orders.findByIdempotencyKey("key")).thenReturn(Optional.of(TradingOrder.restore(snapshot)));
            assertThrows(IllegalStateException.class, () -> recover(f));
        }
        verify(f.orders, never()).compareAndSet(any(), any(), any());
    }

    @Test
    void staleOrderVersionAndConcurrentSettlementDoNotRetryTheAbandonment() {
        Fixture f = fixture();
        when(f.orders.findByIdempotencyKey("key")).thenReturn(Optional.of(TradingOrder.restore(
                f.order.snapshot().toBuilder().version(4).build())));
        assertThrows(ConcurrentModificationException.class, () -> recover(f));
        when(f.orders.findByIdempotencyKey("key")).thenReturn(Optional.of(f.order));
        when(f.orders.compareAndSet(any(), any(), any())).thenReturn(false);
        assertThrows(ConcurrentModificationException.class, () -> recover(f));
        verify(f.orders, times(1)).compareAndSet(any(), any(), any());
    }

    @Test
    void humanAttestationAndEvidenceAreMandatory() {
        Fixture f = fixture();
        assertThrows(IllegalArgumentException.class, () -> f.service.abandon("key", 3, 8, "client", "operator",
                "reason", "evidence", "51603"));
        assertThrows(IllegalArgumentException.class, () -> f.service.abandon("key", 3, 8, "client", "operator",
                "reason", "", UnknownOrderRecoveryService.CONFIRMATION));
        verify(f.okx, never()).getOrder(any());
    }

    private static TradingOrder recover(Fixture f) {
        return f.service.abandon("key", 3, 8, "client", "operator-7", "submission not accepted",
                "exchange-support-ticket-123", UnknownOrderRecoveryService.CONFIRMATION);
    }

    private static Fixture fixture() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        var orders = mock(TradingOrderRepository.class);
        var state = mock(TradingStateStore.class);
        var safety = mock(FundSafetyService.class);
        var leadership = mock(TradingLeadershipService.class);
        var okx = mock(OkxApi.class);
        var meters = new SimpleMeterRegistry();
        Instant old = Instant.now().minusSeconds(3600);
        TradingOrder order = TradingOrder.restore(TradingOrder.Snapshot.builder().id(1L).idempotencyKey("key")
                .clientOrderId("client").instId("BTC-USDT").status(OrderStatus.SUBMIT_UNKNOWN).version(3)
                .submittedAt(old).updatedAt(old).createdAt(old).build());
        when(orders.findByIdempotencyKey("key")).thenReturn(Optional.of(order));
        when(orders.compareAndSet(any(), any(), any())).thenReturn(true);
        when(safety.state()).thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.HALTED).setVersion(8));
        when(okx.getOrder(any())).thenReturn(OkxResponse.fail("51603", "Order does not exist"));
        when(okx.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        return new Fixture(new UnknownOrderRecoveryService(orders, state, safety, leadership, properties, okx, meters),
                order, orders, state, safety, okx, meters);
    }

    private record Fixture(UnknownOrderRecoveryService service, TradingOrder order, TradingOrderRepository orders,
            TradingStateStore state, FundSafetyService safety, OkxApi okx, SimpleMeterRegistry meters) {}
}
