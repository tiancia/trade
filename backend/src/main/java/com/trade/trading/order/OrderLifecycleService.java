package com.trade.trading.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

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
                OrderStatus.SUBMITTING,
                next -> next.setSubmittedAt(Instant.now()),
                "submission ownership acquired"
        );
        reservationCounter(result.changed() ? "acquired" : "replay").increment();
        return new OrderReservation(result.order(), result.changed());
    }

    public Optional<TradingOrder> find(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    public OrderTransitionResult markAccepted(String idempotencyKey, String exchangeOrderId) {
        return transition(idempotencyKey, OrderStatus.ACCEPTED,
                next -> next.setExchangeOrderId(exchangeOrderId)
                        .setFailureCode(null)
                        .setFailureMessage(null),
                "exchange accepted order");
    }

    public OrderTransitionResult markPartiallyFilled(
            String idempotencyKey,
            String exchangeOrderId,
            OrderFill fill
    ) {
        return transition(idempotencyKey, OrderStatus.PARTIALLY_FILLED,
                next -> applyFill(next, exchangeOrderId, fill),
                "exchange reported partial fill");
    }

    public OrderTransitionResult markFilled(String idempotencyKey, String exchangeOrderId, OrderFill fill) {
        return transition(idempotencyKey, OrderStatus.FILLED,
                next -> applyFill(next, exchangeOrderId, fill).setCompletedAt(Instant.now()),
                "exchange reported full fill");
    }

    public OrderTransitionResult markCanceled(String idempotencyKey, String exchangeOrderId, OrderFill fill) {
        return transition(idempotencyKey, OrderStatus.CANCELED,
                next -> applyFill(next, exchangeOrderId, fill).setCompletedAt(Instant.now()),
                "exchange reported cancellation");
    }

    public OrderTransitionResult markRejected(String idempotencyKey, String message) {
        return transition(idempotencyKey, OrderStatus.REJECTED,
                next -> next.setFailureCode("EXCHANGE_REJECTED")
                        .setFailureMessage(message)
                        .setCompletedAt(Instant.now()),
                "exchange rejected order");
    }

    public OrderTransitionResult markSubmitUnknown(String idempotencyKey, String message) {
        return transition(idempotencyKey, OrderStatus.SUBMIT_UNKNOWN,
                next -> next.setFailureCode("SUBMIT_RESULT_UNKNOWN")
                        .setFailureMessage(message),
                "submission result is ambiguous and requires reconciliation");
    }

    private OrderTransitionResult transition(
            String idempotencyKey,
            OrderStatus target,
            Consumer<TradingOrder> mutation,
            String reason
    ) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            TradingOrder current = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Order not found: " + idempotencyKey));
            if (current.getStatus() == target) {
                return new OrderTransitionResult(current, false);
            }

            OrderStateMachine.requireTransition(current.getStatus(), target);
            TradingOrder next = copy(current)
                    .setStatus(target)
                    .setVersion(current.getVersion() + 1)
                    .setUpdatedAt(Instant.now());
            mutation.accept(next);
            if (repository.compareAndSet(current, next, reason)) {
                Counter.builder("trade.trading.orders.transitions")
                        .description("Successful durable order state transitions")
                        .tag("from", current.getStatus().name())
                        .tag("to", target.name())
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

    private static TradingOrder applyFill(TradingOrder order, String exchangeOrderId, OrderFill fill) {
        order.setExchangeOrderId(exchangeOrderId);
        if (fill != null) {
            order.setFilledBaseAmount(fill.filledBaseAmount())
                    .setAverageFillPrice(fill.averageFillPrice())
                    .setFee(fill.fee())
                    .setFeeCcy(fill.feeCcy());
        }
        return order;
    }

    private static TradingOrder copy(TradingOrder source) {
        return new TradingOrder()
                .setId(source.getId())
                .setIdempotencyKey(source.getIdempotencyKey())
                .setClientOrderId(source.getClientOrderId())
                .setExchangeOrderId(source.getExchangeOrderId())
                .setDecisionId(source.getDecisionId())
                .setStrategyId(source.getStrategyId())
                .setInstId(source.getInstId())
                .setAction(source.getAction())
                .setSide(source.getSide())
                .setTdMode(source.getTdMode())
                .setOrderType(source.getOrderType())
                .setTargetCurrency(source.getTargetCurrency())
                .setRequestedSize(source.getRequestedSize())
                .setStatus(source.getStatus())
                .setVersion(source.getVersion())
                .setFilledBaseAmount(source.getFilledBaseAmount())
                .setAverageFillPrice(source.getAverageFillPrice())
                .setFee(source.getFee())
                .setFeeCcy(source.getFeeCcy())
                .setFailureCode(source.getFailureCode())
                .setFailureMessage(source.getFailureMessage())
                .setCreatedAt(source.getCreatedAt())
                .setUpdatedAt(source.getUpdatedAt())
                .setSubmittedAt(source.getSubmittedAt())
                .setCompletedAt(source.getCompletedAt());
    }
}
