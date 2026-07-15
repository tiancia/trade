package com.trade.trading.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStateMachineTest {
    @Test
    void acceptsNormalAndReconciliationTransitions() {
        assertTrue(OrderStateMachine.canTransition(OrderStatus.PENDING_SUBMIT, OrderStatus.SUBMITTING));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.SUBMITTING, OrderStatus.ACCEPTED));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(
                OrderStatus.SUBMIT_UNKNOWN,
                OrderStatus.FILLED
        ));
    }

    @Test
    void rejectsTerminalRollbackAndSkippedStates() {
        assertThrows(
                OrderStateMachine.IllegalOrderStateTransitionException.class,
                () -> OrderStateMachine.requireTransition(OrderStatus.FILLED, OrderStatus.ACCEPTED)
        );
        assertThrows(
                OrderStateMachine.IllegalOrderStateTransitionException.class,
                () -> OrderStateMachine.requireTransition(OrderStatus.REJECTED, OrderStatus.SUBMITTING)
        );
    }
}
