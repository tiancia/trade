package com.trade.trading.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.common.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** MyBatis-backed, best-effort persistence for public OKX market data. */
@Component
public class MyBatisOkxMarketDataStore implements OkxMarketDataStore {
    private static final Logger log = LoggerFactory.getLogger(MyBatisOkxMarketDataStore.class);

    private final OkxMarketSnapshotMapper snapshotMapper;
    private final OkxCandleCacheMapper candleMapper;
    private final ObjectMapper objectMapper;
    private final TradingProperties properties;
    private final AtomicLong lastWebSocketTickerWriteAt = new AtomicLong(0L);

    public MyBatisOkxMarketDataStore(
            OkxMarketSnapshotMapper snapshotMapper,
            OkxCandleCacheMapper candleMapper,
            TradingProperties properties
    ) {
        this.snapshotMapper = snapshotMapper;
        this.candleMapper = candleMapper;
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.properties = properties;
    }

    @Override
    public void saveSnapshot(String instId, String source, TickerResp ticker, OrderBookResp orderBook) {
        saveSnapshotWithResult(instId, source, ticker, orderBook);
    }

    @Override
    public SaveResult saveSnapshotWithResult(String instId, String source, TickerResp ticker, OrderBookResp orderBook) {
        TradingProperties.MarketDataPersistenceProperties config = properties.getMarketDataPersistence();
        if (!config.isEnabled() || (ticker == null && orderBook == null)) {
            return SaveResult.SKIPPED;
        }
        if (OkxMarketDataStore.SOURCE_WEBSOCKET_TICKER.equals(source) && !reserveWebSocketTickerWrite(config)) {
            return SaveResult.SKIPPED;
        }

        TickerResp persistedTicker = config.isTickerEnabled() ? ticker : null;
        OrderBookResp persistedOrderBook = config.isOrderBookEnabled() ? orderBook : null;
        if (persistedTicker == null && persistedOrderBook == null) {
            return SaveResult.SKIPPED;
        }

        try {
            snapshotMapper.insert(toSnapshotRow(instId, source, persistedTicker, persistedOrderBook));
            return SaveResult.SAVED;
        } catch (Exception e) {
            // Market persistence is observational; trading must continue when it is unavailable.
            log.warn("Persist OKX market snapshot failed: instId={}, source={}, error={}",
                    instId, source, e.getMessage(), e);
            return SaveResult.FAILED;
        }
    }

    @Override
    public void saveCandles(String instId, String bar, List<CandleResp> candles) {
        saveCandlesWithResult(instId, bar, candles);
    }

    @Override
    public SaveResult saveCandlesWithResult(String instId, String bar, List<CandleResp> candles) {
        TradingProperties.MarketDataPersistenceProperties config = properties.getMarketDataPersistence();
        if (!config.isEnabled() || !config.isCandleEnabled() || candles == null || candles.isEmpty()) {
            return SaveResult.SKIPPED;
        }

        List<OkxCandleCacheRow> rows = candles.stream()
                .map(candle -> toCandleRow(instId, bar, candle))
                .filter(row -> row.getTs() != null && row.getTs() > 0)
                .toList();
        if (rows.isEmpty()) {
            return SaveResult.SKIPPED;
        }

        try {
            candleMapper.upsertBatch(rows);
            return SaveResult.SAVED;
        } catch (Exception e) {
            log.warn("Persist OKX candles failed: instId={}, bar={}, count={}, error={}",
                    instId, bar, rows.size(), e.getMessage(), e);
            return SaveResult.FAILED;
        }
    }

    private boolean reserveWebSocketTickerWrite(TradingProperties.MarketDataPersistenceProperties config) {
        long interval = Math.max(config.getWebsocketTickerMinIntervalMs(), 0L);
        long now = System.currentTimeMillis();
        while (true) {
            long previous = lastWebSocketTickerWriteAt.get();
            if (now - previous < interval) {
                return false;
            }
            if (lastWebSocketTickerWriteAt.compareAndSet(previous, now)) {
                return true;
            }
        }
    }

    private OkxMarketSnapshotRow toSnapshotRow(
            String instId,
            String source,
            TickerResp ticker,
            OrderBookResp orderBook
    ) throws JsonProcessingException {
        return new OkxMarketSnapshotRow()
                .setInstId(instId)
                .setSource(source)
                .setMarketTs(parseLong(ticker == null ? null : ticker.getTs()))
                .setLastPrice(decimal(ticker == null ? null : ticker.getLast()))
                .setLastSize(decimal(ticker == null ? null : ticker.getLastSz()))
                .setBidPrice(decimal(ticker == null ? null : ticker.getBidPx()))
                .setBidSize(decimal(ticker == null ? null : ticker.getBidSz()))
                .setAskPrice(decimal(ticker == null ? null : ticker.getAskPx()))
                .setAskSize(decimal(ticker == null ? null : ticker.getAskSz()))
                .setOpen24h(decimal(ticker == null ? null : ticker.getOpen24h()))
                .setHigh24h(decimal(ticker == null ? null : ticker.getHigh24h()))
                .setLow24h(decimal(ticker == null ? null : ticker.getLow24h()))
                .setVolCcy24h(decimal(ticker == null ? null : ticker.getVolCcy24h()))
                .setVol24h(decimal(ticker == null ? null : ticker.getVol24h()))
                .setOrderBookTs(parseLong(orderBook == null ? null : orderBook.getTs()))
                .setSequenceId(orderBook == null ? null : orderBook.getSeqId())
                .setTickerJson(ticker == null ? null : objectMapper.writeValueAsString(ticker))
                .setOrderBookJson(orderBook == null ? null : objectMapper.writeValueAsString(orderBook));
    }

    private static OkxCandleCacheRow toCandleRow(String instId, String bar, CandleResp candle) {
        return new OkxCandleCacheRow()
                .setInstId(instId)
                .setBar(bar)
                .setTs(parseLong(candle == null ? null : candle.getTs()))
                .setOpen(decimal(candle == null ? null : candle.getOpen()))
                .setHigh(decimal(candle == null ? null : candle.getHigh()))
                .setLow(decimal(candle == null ? null : candle.getLow()))
                .setClose(decimal(candle == null ? null : candle.getClose()))
                .setVol(decimal(candle == null ? null : candle.getVol()))
                .setVolCcy(decimal(candle == null ? null : candle.getVolCcy()))
                .setVolCcyQuote(decimal(candle == null ? null : candle.getVolCcyQuote()))
                .setConfirm(candle == null ? null : candle.getConfirm());
    }

    private static java.math.BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : TradingMath.decimal(value);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
