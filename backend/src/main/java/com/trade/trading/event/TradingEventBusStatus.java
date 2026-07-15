package com.trade.trading.event;

/** Lightweight runtime view in addition to the Micrometer metrics. */
public record TradingEventBusStatus(
        boolean running,
        boolean accepting,
        int queueDepth,
        int queueCapacity,
        int handlerCount,
        long accepted,
        long dropped,
        long consumed,
        long failed
) {
}
