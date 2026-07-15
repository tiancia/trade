package com.trade.trading.event;

/** Observable result of applying the configured queue backpressure policy. */
public enum TradingEventPublishResult {
    ACCEPTED(true),
    ACCEPTED_AFTER_DROPPING_OLDEST(true),
    DROPPED_LATEST(false),
    TIMED_OUT(false),
    REJECTED_NOT_RUNNING(false),
    INTERRUPTED(false);

    private final boolean accepted;

    TradingEventPublishResult(boolean accepted) {
        this.accepted = accepted;
    }

    public boolean accepted() {
        return accepted;
    }
}
