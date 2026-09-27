package com.trade.trading.application.event;

/** Consumer outcome used for metrics without leaking persistence details. */
public enum TradingEventHandlingResult {
    PROCESSED,
    SKIPPED,
    FAILED
}
