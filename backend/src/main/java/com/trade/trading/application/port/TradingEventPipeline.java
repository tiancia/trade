package com.trade.trading.application.port;

import com.trade.trading.application.event.TradingEventBusStatus;

/** Application-facing lifecycle and operational view of event delivery. */
public interface TradingEventPipeline {
    void start();
    TradingEventBusStatus status();
}
