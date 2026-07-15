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
