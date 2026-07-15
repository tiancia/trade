package com.trade.trading.event;

/** Producer-facing port; publishing never performs database I/O itself. */
@FunctionalInterface
public interface TradingEventPublisher {
    TradingEventPublishResult publish(TradingEvent event);
}
