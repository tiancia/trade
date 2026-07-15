package com.trade.trading.order;

/** Durable lifecycle states for an order sent to an external exchange. */
public enum OrderStatus {
    PENDING_SUBMIT,
    SUBMITTING,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_PENDING,
    CANCELED,
    REJECTED,
    SUBMIT_UNKNOWN;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED;
    }
}
