package com.trade.trading.event;

/** Consumer outcome used for metrics without leaking persistence details. */
public enum TradingEventHandlingResult {
    PROCESSED,
    SKIPPED,
    FAILED
}
