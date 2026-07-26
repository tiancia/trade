package com.trade.trading.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
