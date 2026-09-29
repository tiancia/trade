package com.trade.trading.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** A domain state change; application owns optimistic persistence and audit delivery. */
public final class OrderChange {
    private final OrderStatus target;
    private final Consumer<TradingOrder.Snapshot.SnapshotBuilder> mutation;
    private final String reason;

    private OrderChange(OrderStatus target, Consumer<TradingOrder.Snapshot.SnapshotBuilder> mutation, String reason) {
        this.target = target;
        this.mutation = mutation;
        this.reason = reason;
    }

    public OrderStatus target() { return target; }
    public String reason() { return reason; }
    public static OrderChange submitting(Instant now) {
        return new OrderChange(OrderStatus.SUBMITTING, next -> next.submittedAt(now), "submission ownership acquired");
    }
    public TradingOrder apply(TradingOrder current, Instant now) {
        return current.change(target, next -> {
            mutation.accept(next);
            if (current.getCompletedAt() != null) { next.completedAt(current.getCompletedAt()); }
        }, now);
    }
    public static OrderChange markAccepted(String exchangeOrderId, Instant now) {
        return new OrderChange(OrderStatus.ACCEPTED,
                next -> next.exchangeOrderId(exchangeOrderId)
                        .failureCode(null)
                        .failureMessage(null),
                "exchange accepted order");
    }

    public static OrderChange markPartiallyFilled(
            String exchangeOrderId,
            OrderFill fill,
            Instant now
    ) {
        return new OrderChange(OrderStatus.PARTIALLY_FILLED,
                next -> applyFill(next, exchangeOrderId, fill),
                "exchange reported partial fill");
    }

    public static OrderChange markFilled(String exchangeOrderId, OrderFill fill, Instant now) {
        return new OrderChange(OrderStatus.FILLED,
                next -> {
                    applyFill(next, exchangeOrderId, fill);
                    next.completedAt(now);
                },
                "exchange reported full fill");
    }

    public static OrderChange markCanceled(String exchangeOrderId, OrderFill fill, Instant now) {
        return new OrderChange(OrderStatus.CANCELED,
                next -> {
                    applyFill(next, exchangeOrderId, fill);
                    next.completedAt(now);
                },
                "exchange reported cancellation");
    }

    public static OrderChange markRejected(String message, Instant now) {
        return new OrderChange(OrderStatus.REJECTED,
                next -> {
                    next.failureCode("EXCHANGE_REJECTED")
                            .failureMessage(message);
                    next.completedAt(now);
                },
                "exchange rejected order");
    }

    public static OrderChange markSubmissionBlocked(
            String code,
            String message,
            Instant now
    ) {
        return new OrderChange(OrderStatus.REJECTED,
                next -> {
                    next.failureCode(code)
                            .failureMessage(message);
                    next.completedAt(now);
                },
                "local safety gate blocked external submission");
    }

    public static OrderChange markSubmitUnknown(String message, Instant now) {
        return new OrderChange(OrderStatus.SUBMIT_UNKNOWN,
                next -> next.failureCode("SUBMIT_RESULT_UNKNOWN")
                        .failureMessage(message),
                "submission result is ambiguous and requires reconciliation");
    }
    public static boolean hasSameExchangeState(TradingOrder left, TradingOrder right) {
        return Objects.equals(left.getExchangeOrderId(), right.getExchangeOrderId())
                && decimalEquals(left.getFilledBaseAmount(), right.getFilledBaseAmount())
                && decimalEquals(left.getAverageFillPrice(), right.getAverageFillPrice())
                && decimalEquals(left.getFee(), right.getFee())
                && Objects.equals(left.getFeeCcy(), right.getFeeCcy())
                && Objects.equals(left.getFailureCode(), right.getFailureCode())
                && Objects.equals(left.getFailureMessage(), right.getFailureMessage())
                && Objects.equals(left.getCompletedAt(), right.getCompletedAt());
    }
    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
    private static TradingOrder.Snapshot.SnapshotBuilder applyFill(TradingOrder.Snapshot.SnapshotBuilder order, String exchangeOrderId, OrderFill fill) {
        order.exchangeOrderId(exchangeOrderId);
        if (fill != null) {
            order.filledBaseAmount(fill.filledBaseAmount())
                    .averageFillPrice(fill.averageFillPrice())
                    .fee(fill.fee())
                    .feeCcy(fill.feeCcy());
        }
        return order;
    }
}
