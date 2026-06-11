package com.trade.trading.persistence;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBatisOkxMarketDataStoreTest {

    @Test
    void mapsTickerOrderBookAndCandlesToDatabaseRows() {
        CapturingSnapshotMapper snapshotMapper = new CapturingSnapshotMapper();
        CapturingCandleMapper candleMapper = new CapturingCandleMapper();
        MyBatisOkxMarketDataStore store = store(snapshotMapper, candleMapper, new TradingProperties());

        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setTs("1710000000000");
        ticker.setLast("65000.12");
        ticker.setBidPx("65000.10");
        ticker.setAskPx("65000.14");
        ticker.setVol24h("123.45");

        OrderBookResp orderBook = new OrderBookResp();
        orderBook.setTs("1710000000001");
        orderBook.setSeqId(42L);

        store.saveSnapshot("BTC-USDT", OkxMarketDataStore.SOURCE_REST_DECISION, ticker, orderBook);
        store.saveCandles("BTC-USDT", "1m", List.of(
                candle("1710000000000", "65000.12"),
                candle("invalid", "65001.00")
        ));

        OkxMarketSnapshotRow snapshot = snapshotMapper.row;
        assertNotNull(snapshot);
        assertEquals("BTC-USDT", snapshot.getInstId());
        assertEquals(0, new BigDecimal("65000.12").compareTo(snapshot.getLastPrice()));
        assertEquals(42L, snapshot.getSequenceId());
        assertTrue(snapshot.getTickerJson().contains("BTC-USDT"));
        assertNotNull(snapshot.getOrderBookJson());

        assertEquals(1, candleMapper.rows.size());
        assertEquals("1m", candleMapper.rows.getFirst().getBar());
        assertEquals(0, new BigDecimal("65000.12").compareTo(candleMapper.rows.getFirst().getClose()));
    }

    @Test
    void throttlesHighFrequencyWebSocketTickerWrites() {
        CapturingSnapshotMapper snapshotMapper = new CapturingSnapshotMapper();
        TradingProperties properties = new TradingProperties();
        properties.getMarketDataPersistence().setWebsocketTickerMinIntervalMs(60_000L);
        MyBatisOkxMarketDataStore store = store(snapshotMapper, new CapturingCandleMapper(), properties);
        TickerResp ticker = new TickerResp();
        ticker.setTs("1710000000000");

        store.saveSnapshot("BTC-USDT", OkxMarketDataStore.SOURCE_WEBSOCKET_TICKER, ticker, null);
        store.saveSnapshot("BTC-USDT", OkxMarketDataStore.SOURCE_WEBSOCKET_TICKER, ticker, null);

        assertEquals(1, snapshotMapper.insertCount);
    }

    @Test
    void databaseFailuresDoNotEscapeIntoTradingFlow() {
        TradingProperties properties = new TradingProperties();
        OkxMarketSnapshotMapper failingSnapshotMapper = row -> {
            throw new IllegalStateException("database unavailable");
        };
        OkxCandleCacheMapper failingCandleMapper = new CapturingCandleMapper() {
            @Override
            public void upsertBatch(List<OkxCandleCacheRow> rows) {
                throw new IllegalStateException("database unavailable");
            }
        };
        MyBatisOkxMarketDataStore store = store(failingSnapshotMapper, failingCandleMapper, properties);

        assertDoesNotThrow(() -> store.saveSnapshot(
                "BTC-USDT",
                OkxMarketDataStore.SOURCE_REST_DECISION,
                new TickerResp(),
                null
        ));
        assertDoesNotThrow(() -> store.saveCandles(
                "BTC-USDT",
                "1m",
                List.of(candle("1710000000000", "65000"))
        ));
    }

    private static MyBatisOkxMarketDataStore store(
            OkxMarketSnapshotMapper snapshotMapper,
            OkxCandleCacheMapper candleMapper,
            TradingProperties properties
    ) {
        return new MyBatisOkxMarketDataStore(snapshotMapper, candleMapper, properties);
    }

    private static CandleResp candle(String ts, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(ts);
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        candle.setVol("1");
        candle.setConfirm("1");
        return candle;
    }

    private static class CapturingSnapshotMapper implements OkxMarketSnapshotMapper {
        private OkxMarketSnapshotRow row;
        private int insertCount;

        @Override
        public void insert(OkxMarketSnapshotRow row) {
            this.row = row;
            insertCount++;
        }
    }

    private static class CapturingCandleMapper implements OkxCandleCacheMapper {
        private final List<OkxCandleCacheRow> rows = new ArrayList<>();

        @Override
        public List<OkxCandleCacheRow> findRange(String instId, String bar, long fromTs, long toTs) {
            return List.of();
        }

        @Override
        public void upsert(OkxCandleCacheRow row) {
            rows.add(row);
        }

        @Override
        public void upsertBatch(List<OkxCandleCacheRow> rows) {
            this.rows.addAll(rows);
        }
    }
}
