package com.trade.trading.application.order;

import com.trade.trading.domain.order.OrderChange;
import com.trade.trading.application.port.TradingOrderRepository;
import com.trade.trading.domain.order.OrderFill;
import com.trade.trading.domain.order.OrderReservation;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.OrderTransitionResult;
import com.trade.trading.domain.order.TradingOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** Coordinates idempotent reservation and optimistic, audited state changes. */
@Component
public class OrderLifecycleService {
    private static final int MAX_CAS_ATTEMPTS = 3;

    private final TradingOrderRepository repository;
    private final MeterRegistry meterRegistry;

    public OrderLifecycleService(TradingOrderRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public OrderReservation reserve(OrderSubmission submission) {
        TradingOrder order = repository.createOrGet(submission);
        if (order.getStatus() != OrderStatus.PENDING_SUBMIT) {
            reservationCounter("replay").increment();
            return new OrderReservation(order, false);
        }

        OrderTransitionResult result = transition(
                submission.idempotencyKey(),
                OrderChange.submitting(Instant.now())
        );
        reservationCounter(result.changed() ? "acquired" : "replay").increment();
        return new OrderReservation(result.order(), result.changed());
    }

    public Optional<TradingOrder> find(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    public OrderTransitionResult markAccepted(String idempotencyKey, String exchangeOrderId) {
        return transition(idempotencyKey, OrderChange.markAccepted(exchangeOrderId, Instant.now()));
    }

    public OrderTransitionResult markPartiallyFilled(
            String idempotencyKey,
            String exchangeOrderId,
            OrderFill fill
    ) {
        return transition(idempotencyKey, OrderChange.markPartiallyFilled(exchangeOrderId, fill, Instant.now()));
    }

    public OrderTransitionResult markFilled(String idempotencyKey, String exchangeOrderId, OrderFill fill) {
        return transition(idempotencyKey, OrderChange.markFilled(exchangeOrderId, fill, Instant.now()));
    }

    public OrderTransitionResult markCanceled(String idempotencyKey, String exchangeOrderId, OrderFill fill) {
        return transition(idempotencyKey, OrderChange.markCanceled(exchangeOrderId, fill, Instant.now()));
    }

    public OrderTransitionResult markRejected(String idempotencyKey, String message) {
        return transition(idempotencyKey, OrderChange.markRejected(message, Instant.now()));
    }

    public OrderTransitionResult markSubmissionBlocked(
            String idempotencyKey,
            String code,
            String message
    ) {
        return transition(idempotencyKey, OrderChange.markSubmissionBlocked(code, message, Instant.now()));
    }

    public OrderTransitionResult markSubmitUnknown(String idempotencyKey, String message) {
        return transition(idempotencyKey, OrderChange.markSubmitUnknown(message, Instant.now()));
    }

    private OrderTransitionResult transition(
            String idempotencyKey, OrderChange change
    ) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            TradingOrder current = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Order not found: " + idempotencyKey));
            TradingOrder next = change.apply(current, Instant.now());
            if (current.getStatus() == change.target() && OrderChange.hasSameExchangeState(current, next)) {
                return new OrderTransitionResult(current, false);
            }
            if (repository.compareAndSet(current, next, change.reason())) {
                Counter.builder("trade.trading.orders.transitions")
                        .description("Successful durable order state transitions")
                        .tag("from", current.getStatus().name())
                        .tag("to", change.target().name())
                        .register(meterRegistry)
                        .increment();
                return new OrderTransitionResult(next, true);
            }
            Counter.builder("trade.trading.orders.cas.conflicts")
                    .description("Optimistic-lock conflicts while advancing order state")
                    .register(meterRegistry)
                    .increment();
        }
        throw new IllegalStateException("Concurrent order update did not converge: " + idempotencyKey);
    }

    private Counter reservationCounter(String outcome) {
        return Counter.builder("trade.trading.orders.reservations")
                .description("Order idempotency reservation outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

}
