package com.trade.trading.event;

import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;

/** Ticker and/or order-book values observed together on one collection path. */
public record MarketSnapshotPayload(TickerResp ticker, OrderBookResp orderBook) implements TradingEventPayload {
    public MarketSnapshotPayload {
        if (ticker == null && orderBook == null) {
            throw new IllegalArgumentException("ticker and orderBook cannot both be null");
        }
    }
}
