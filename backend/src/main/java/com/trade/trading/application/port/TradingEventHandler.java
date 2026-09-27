package com.trade.trading.application.port;

import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventHandlingResult;

/** Independently isolated consumer of trading events. */
public interface TradingEventHandler {
    String name();

    boolean supports(TradingEvent event);

    TradingEventHandlingResult handle(TradingEvent event);
}
