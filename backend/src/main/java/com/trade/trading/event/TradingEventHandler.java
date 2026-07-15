package com.trade.trading.event;

/** Independently isolated consumer of trading events. */
public interface TradingEventHandler {
    String name();

    boolean supports(TradingEvent event);

    TradingEventHandlingResult handle(TradingEvent event);
}
