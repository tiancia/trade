package com.trade.trading.persistence;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;

import java.util.List;

/**
 * Stores public OKX market data consumed by the trading module.
 *
 * <p>Implementations must treat persistence as best effort: a database failure
 * must not prevent the strategy engine from evaluating otherwise valid data.</p>
 */
public interface OkxMarketDataStore {
    String SOURCE_REST_DECISION = "REST_DECISION";
    String SOURCE_REST_EVENT_FALLBACK = "REST_EVENT_FALLBACK";
    String SOURCE_WEBSOCKET_TICKER = "WEBSOCKET_TICKER";

    /** Saves one ticker/order-book snapshot from the specified collection path. */
    void saveSnapshot(String instId, String source, TickerResp ticker, OrderBookResp orderBook);

    /** Upserts candles by instrument, bar, and exchange timestamp. */
    void saveCandles(String instId, String bar, List<CandleResp> candles);
}
