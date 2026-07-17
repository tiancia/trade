package com.trade.trading.market;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;

import java.util.List;
import java.util.Optional;

/**
 * Best-effort shared cache for public market data read frequently by trading.
 * Implementations must degrade to misses when the cache service is unavailable.
 */
public interface HotMarketDataCache {
    CacheWriteResult putSnapshot(
            String instId,
            TickerResp ticker,
            OrderBookResp orderBook
    );

    CacheWriteResult putCandles(String instId, String bar, List<CandleResp> candles);

    Optional<TickerResp> latestTicker(String instId);

    Optional<OrderBookResp> latestOrderBook(String instId);

    List<CandleResp> recentCandles(String instId, String bar, int limit);

    enum CacheWriteResult {
        CACHED,
        SKIPPED,
        FAILED
    }
}
