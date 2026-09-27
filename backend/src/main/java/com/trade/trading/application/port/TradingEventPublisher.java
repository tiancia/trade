package com.trade.trading.application.port;

import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventPublishResult;

/** Producer-facing port; publishing never performs database I/O itself. */
@FunctionalInterface
public interface TradingEventPublisher {
    TradingEventPublishResult publish(TradingEvent event);
}
