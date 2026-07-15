package com.trade.trading.event;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.persistence.OkxMarketDataStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataPersistenceEventHandlerTest {

    @Test
    void mapsSnapshotAndCandleEventsToStore() {
        CapturingStore store = new CapturingStore();
        MarketDataPersistenceEventHandler handler = new MarketDataPersistenceEventHandler(store);
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");
        CandleResp candle = new CandleResp();
        candle.setTs("1710000000000");

        TradingEventHandlingResult snapshotResult = handler.handle(TradingEvent.marketSnapshot(
                "BTC-USDT", TradingEventSource.OKX_REST_DECISION, "decision-1", ticker, null
        ));
        TradingEventHandlingResult candleResult = handler.handle(TradingEvent.candleBatch(
                "BTC-USDT", TradingEventSource.OKX_REST_DECISION, "decision-1", "1m", List.of(candle)
        ));

        assertEquals(TradingEventHandlingResult.PROCESSED, snapshotResult);
        assertEquals(TradingEventHandlingResult.PROCESSED, candleResult);
        assertEquals("REST_DECISION", store.source);
        assertEquals("50000", store.ticker.getLast());
        assertEquals("1m", store.bar);
        assertEquals(1, store.candles.size());
    }

    private static class CapturingStore implements OkxMarketDataStore {
        private String source;
        private TickerResp ticker;
        private String bar;
        private List<CandleResp> candles;

        @Override
        public void saveSnapshot(String instId, String source, TickerResp ticker, OrderBookResp orderBook) {
            this.source = source;
            this.ticker = ticker;
        }

        @Override
        public void saveCandles(String instId, String bar, List<CandleResp> candles) {
            this.bar = bar;
            this.candles = candles;
        }
    }
}
