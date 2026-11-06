package com.trade.trading.application.order;

import com.trade.trading.domain.order.OrderFill;
import com.trade.trading.domain.order.OrderReservation;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.OrderTransitionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;

class OrderLifecycleServiceTest {
    @Test
    void concurrentReservationsHaveExactlyOneSubmissionOwner() throws Exception {
        InMemoryTradingOrderRepository repository = new InMemoryTradingOrderRepository();
        OrderLifecycleService service = new OrderLifecycleService(repository, new SimpleMeterRegistry());
        OrderSubmission submission = submission("same-business-order");
        Callable<OrderReservation> reserve = () -> service.reserve(submission);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<OrderReservation> results = executor.invokeAll(List.of(reserve, reserve)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            assertEquals(1, results.stream().filter(OrderReservation::acquired).count());
            assertEquals(OrderStatus.SUBMITTING, service.find("same-business-order").orElseThrow().getStatus());
            assertEquals(1, repository.transitionCount());
        }
    }

    @Test
    void cumulativePartialFillRefreshAdvancesVersionWithinSameStatus() {
        InMemoryTradingOrderRepository repository = new InMemoryTradingOrderRepository();
        OrderLifecycleService service = new OrderLifecycleService(repository, new SimpleMeterRegistry());
        OrderSubmission submission = submission("partial-refresh");
        service.reserve(submission);
        service.markAccepted(submission.idempotencyKey(), "exchange-1");
        service.markPartiallyFilled(
                submission.idempotencyKey(),
                "exchange-1",
                new OrderFill(new BigDecimal("0.001"), new BigDecimal("50000"), BigDecimal.ZERO, "USDT")
        );

        OrderTransitionResult refreshed = service.markPartiallyFilled(
                submission.idempotencyKey(),
                "exchange-1",
                new OrderFill(new BigDecimal("0.002"), new BigDecimal("50010"), BigDecimal.ZERO, "USDT")
        );

        assertEquals(true, refreshed.changed());
        assertEquals(0, new BigDecimal("0.002").compareTo(refreshed.order().getFilledBaseAmount()));
        assertEquals(4L, refreshed.order().getVersion());
    }

    @Test
    void staleTerminalObservationCannotEraseAnAlreadyCommittedPartialFill() {
        InMemoryTradingOrderRepository repository = new InMemoryTradingOrderRepository();
        OrderLifecycleService service = new OrderLifecycleService(repository, new SimpleMeterRegistry());
        String key = "stale-filled";
        service.reserve(submission(key));
        service.markPartiallyFilled(key, "exchange-1",
                new OrderFill(new BigDecimal("0.002"), new BigDecimal("50000"), BigDecimal.ZERO, "USDT"));

        assertThrows(IllegalStateException.class, () -> service.markFilled(key, "exchange-1",
                new OrderFill(new BigDecimal("0.001"), new BigDecimal("50000"), BigDecimal.ZERO, "USDT")));

        assertEquals(OrderStatus.PARTIALLY_FILLED, service.find(key).orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("0.002").compareTo(service.find(key).orElseThrow().getFilledBaseAmount()));
    }

    @Test
    void conflictingExchangeIdentityIsRejectedAfterCompareAndSetRetry() {
        var repository = spy(new InMemoryTradingOrderRepository());
        var service = new OrderLifecycleService(repository, new SimpleMeterRegistry());
        String key = "conflicting-identity";
        service.reserve(submission(key));
        confirmExchangeIdentityDuringNextUpdate(repository, service, key);

        assertThrows(IllegalStateException.class, () -> service.markFilled(key, "conflicting-exchange",
                new OrderFill(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, "USDT")));

        var saved = service.find(key).orElseThrow();
        assertEquals("confirmed-exchange", saved.getExchangeOrderId());
        assertEquals(OrderStatus.ACCEPTED, saved.getStatus());
        assertEquals(2, repository.transitionCount());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void missingExchangeIdentityPreservesConcurrentAcknowledgement(String missingId) {
        var repository = spy(new InMemoryTradingOrderRepository());
        var service = new OrderLifecycleService(repository, new SimpleMeterRegistry());
        String key = "missing-identity";
        service.reserve(submission(key));
        confirmExchangeIdentityDuringNextUpdate(repository, service, key);

        service.markFilled(key, missingId,
                new OrderFill(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, "USDT"));

        var saved = service.find(key).orElseThrow();
        assertEquals("confirmed-exchange", saved.getExchangeOrderId());
        assertEquals(OrderStatus.FILLED, saved.getStatus());
        assertEquals(3, repository.transitionCount());
    }

    private static void confirmExchangeIdentityDuringNextUpdate(InMemoryTradingOrderRepository repository,
            OrderLifecycleService service, String key) {
        doAnswer(invocation -> {
            // Another writer acknowledges the order between this writer's read and CAS.
            doCallRealMethod().when(repository).compareAndSet(any(), any(), anyString());
            service.markAccepted(key, "confirmed-exchange");
            return false;
        }).when(repository).compareAndSet(any(), any(), anyString());
    }

    private static OrderSubmission submission(String key) {
        return new OrderSubmission(
                key,
                "stbu123",
                "decision-1",
                "strategy-1",
                "BTC-USDT",
                "BUY",
                "buy",
                "cash",
                "market",
                "quote_ccy",
                BigDecimal.TEN
        );
    }
}
