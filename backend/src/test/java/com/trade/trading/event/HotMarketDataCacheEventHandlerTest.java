package com.trade.trading.event;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.market.HotMarketDataCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotMarketDataCacheEventHandlerTest {

    @Test
    void mapsSnapshotAndCandleEventsToHotCache() {
        CapturingCache cache = new CapturingCache();
        HotMarketDataCacheEventHandler handler = new HotMarketDataCacheEventHandler(cache);
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");
        CandleResp candle = new CandleResp();
        candle.setTs("1710000000000");

        TradingEventHandlingResult snapshotResult = handler.handle(TradingEvent.marketSnapshot(
                "BTC-USDT", TradingEventSource.OKX_WEBSOCKET, null, ticker, null
        ));
        TradingEventHandlingResult candleResult = handler.handle(TradingEvent.candleBatch(
                "BTC-USDT", TradingEventSource.OKX_WEBSOCKET, null, "1m", List.of(candle)
        ));

        assertEquals(TradingEventHandlingResult.PROCESSED, snapshotResult);
        assertEquals(TradingEventHandlingResult.PROCESSED, candleResult);
        assertEquals("BTC-USDT", cache.instId);
        assertEquals("50000", cache.ticker.getLast());
        assertEquals("1m", cache.bar);
        assertEquals("1710000000000", cache.candles.getFirst().getTs());
    }

    private static class CapturingCache implements HotMarketDataCache {
        private String instId;
        private TickerResp ticker;
        private String bar;
        private List<CandleResp> candles;

        @Override
        public CacheWriteResult putSnapshot(String instId, TickerResp ticker, OrderBookResp orderBook) {
            this.instId = instId;
            this.ticker = ticker;
            return CacheWriteResult.CACHED;
        }

        @Override
        public CacheWriteResult putCandles(String instId, String bar, List<CandleResp> candles) {
            this.instId = instId;
            this.bar = bar;
            this.candles = candles;
            return CacheWriteResult.CACHED;
        }

        @Override
        public Optional<TickerResp> latestTicker(String instId) {
            return Optional.empty();
        }

        @Override
        public Optional<OrderBookResp> latestOrderBook(String instId) {
            return Optional.empty();
        }

        @Override
        public List<CandleResp> recentCandles(String instId, String bar, int limit) {
            return List.of();
        }
    }
}
