package com.trade.trading.application.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.trading.application.event.CandleBatchPayload;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.port.HotMarketDataCache;
import com.trade.trading.application.port.TradingEventPublisher;
import com.trade.trading.application.port.TradingMarketFeed;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TradingMarketDataQueryServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T08:04:20Z"), ZoneOffset.UTC);
    private final TradingProperties properties = new TradingProperties();
    private final OkxApi api = mock(OkxApi.class);
    private final TradingMarketFeed feed = mock(TradingMarketFeed.class);
    private final HotMarketDataCache hotCache = mock(HotMarketDataCache.class);
    private final TradingEventPublisher publisher = mock(TradingEventPublisher.class);
    private final TradingMarketDataQueryService service = new TradingMarketDataQueryService(
            api, properties, feed, hotCache, publisher, CLOCK);

    @Test
    void prefersFreshWebSocketCandlesAndReturnsChronologicalSeries() {
        when(feed.recentCandles("1m", 3)).thenReturn(List.of(
                candle("1m", 0, "103"), candle("1m", -2, "101"), candle("1m", -1, "102")));

        CandleSeries result = service.recentCandles("BTC-USDT", "1m", 3);

        assertEquals("WEBSOCKET", result.source());
        assertEquals(List.of("101", "102", "103"), result.candles().stream().map(CandleResp::getClose).toList());
        verifyNoInteractions(hotCache, api);
    }

    @Test
    void refusesNonemptyOldRedisHistoryAndFetchesLatestCandles() {
        when(hotCache.recentCandles("BTC-USDT", "1m", 3)).thenReturn(List.of(
                candle("1m", -3, "100"), candle("1m", -4, "99"), candle("1m", -5, "98")));
        when(api.getCandles(any(CandlesReq.class))).thenReturn(OkxResponse.success(List.of(
                candle("1m", 0, "103"), candle("1m", -1, "102"), candle("1m", -2, "101"))));

        CandleSeries result = service.recentCandles("BTC-USDT", "1m", 3);

        assertEquals("OKX_REST", result.source());
        assertEquals("103", result.candles().getLast().getClose());
        verify(api).getCandles(any(CandlesReq.class));
    }

    @Test
    void supplementsNewWebSocketWithRestHistoryWithoutOverwritingAnInFlightUpdate() {
        when(feed.recentCandles("1m", 3)).thenReturn(List.of(candle("1m", 0, "103")),
                List.of(candle("1m", 0, "104")));
        when(api.getCandles(any(CandlesReq.class))).thenReturn(OkxResponse.success(List.of(
                candle("1m", 0, "102"), candle("1m", -1, "101"), candle("1m", -2, "100"))));

        CandleSeries result = service.recentCandles("BTC-USDT", "1m", 3);

        assertEquals(3, result.candles().size());
        assertEquals("104", result.candles().getLast().getClose());
        assertEquals("WEBSOCKET", result.source());
        ArgumentCaptor<TradingEvent> event = ArgumentCaptor.forClass(TradingEvent.class);
        verify(publisher).publish(event.capture());
        CandleBatchPayload payload = (CandleBatchPayload) event.getValue().payload();
        assertEquals("104", payload.candles().getLast().getClose());
    }

    @Test
    void usesFullFreshRedisHistoryToSupplementWebSocketWithoutHttp() {
        when(feed.recentCandles("5m", 3)).thenReturn(List.of(candle("5m", 0, "105")));
        when(hotCache.recentCandles("BTC-USDT", "5m", 3)).thenReturn(List.of(
                candle("5m", 0, "104"), candle("5m", -1, "103"), candle("5m", -2, "102")));

        CandleSeries result = service.recentCandles("BTC-USDT", "5m", 3);

        assertEquals(3, result.candles().size());
        assertEquals("105", result.candles().getLast().getClose());
        verifyNoInteractions(api);
    }

    @Test
    void requestsMissingHistoryEvenWhenRedisContainsACurrentCandle() {
        when(hotCache.recentCandles("BTC-USDT", "1H", 3))
                .thenReturn(List.of(candle("1H", 0, "100")));
        when(api.getCandles(any(CandlesReq.class))).thenReturn(OkxResponse.success(List.of(
                candle("1H", 0, "101"), candle("1H", -1, "99"), candle("1H", -2, "98"))));

        CandleSeries result = service.recentCandles("BTC-USDT", "1h", 3);

        assertEquals(3, result.candles().size());
        assertEquals("101", result.candles().getLast().getClose());
        assertEquals("OKX_REST", result.source());
    }

    @Test
    void staleWebSocketCannotHideFreshRedisCandles() {
        when(feed.recentCandles("15m", 2)).thenReturn(List.of(
                candle("15m", -1, "99"), candle("15m", -2, "98")));
        when(hotCache.recentCandles("BTC-USDT", "15m", 2)).thenReturn(List.of(
                candle("15m", 0, "101"), candle("15m", -1, "100")));

        CandleSeries result = service.recentCandles("BTC-USDT", "15m", 2);

        assertEquals("HOT_CACHE", result.source());
        assertEquals("101", result.candles().getLast().getClose());
        verifyNoInteractions(api);
    }

    @Test
    void reconnectingWebSocketCannotHideAGapInRetainedHistory() {
        when(feed.recentCandles("1m", 3)).thenReturn(List.of(candle("1m", 0, "103")));
        when(hotCache.recentCandles("BTC-USDT", "1m", 3)).thenReturn(List.of(
                candle("1m", 0, "102"), candle("1m", -4, "99"), candle("1m", -5, "98")));
        when(api.getCandles(any(CandlesReq.class))).thenReturn(OkxResponse.success(List.of(
                candle("1m", 0, "102"), candle("1m", -1, "101"), candle("1m", -2, "100"))));

        CandleSeries result = service.recentCandles("BTC-USDT", "1m", 3);

        assertEquals(List.of("100", "101", "103"), result.candles().stream().map(CandleResp::getClose).toList());
        verify(api).getCandles(any(CandlesReq.class));
    }

    private static CandleResp candle(String bar, int offset, String close) {
        long interval = CandleFreshness.intervalMillis(bar);
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(CLOCK.millis() / interval * interval + offset * interval));
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        return candle;
    }
}
