package com.trade.trading.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit order state machine. The database compare-and-set in the repository
 * applies these rules atomically when several workers observe the same order.
 */
public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = transitions();

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return from != null && to != null && TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalOrderStateTransitionException(from, to);
        }
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        Map<OrderStatus, Set<OrderStatus>> result = new EnumMap<>(OrderStatus.class);
        result.put(OrderStatus.PENDING_SUBMIT, EnumSet.of(OrderStatus.SUBMITTING));
        result.put(OrderStatus.SUBMITTING, EnumSet.of(
                OrderStatus.ACCEPTED,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED,
                OrderStatus.SUBMIT_UNKNOWN
        ));
        result.put(OrderStatus.SUBMIT_UNKNOWN, EnumSet.of(
                OrderStatus.ACCEPTED,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED
        ));
        result.put(OrderStatus.ACCEPTED, EnumSet.of(
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCEL_PENDING,
                OrderStatus.CANCELED
        ));
        result.put(OrderStatus.PARTIALLY_FILLED, EnumSet.of(
                OrderStatus.FILLED,
                OrderStatus.CANCEL_PENDING,
                OrderStatus.CANCELED
        ));
        result.put(OrderStatus.CANCEL_PENDING, EnumSet.of(
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELED
        ));
        result.put(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class));
        result.put(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class));
        result.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(result);
    }

    public static final class IllegalOrderStateTransitionException extends IllegalStateException {
        public IllegalOrderStateTransitionException(OrderStatus from, OrderStatus to) {
            super("Illegal order state transition: " + from + " -> " + to);
        }
    }
}
