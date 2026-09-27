package com.trade.trading.application.event;

/** Stable event names used by consumers, logs, and low-cardinality metrics. */
public enum TradingEventType {
    MARKET_SNAPSHOT,
    CANDLE_BATCH
}
