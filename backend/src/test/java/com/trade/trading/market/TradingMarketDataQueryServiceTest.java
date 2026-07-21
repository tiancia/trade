package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.event.TradingEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingMarketDataQueryServiceTest {
    @Test
    void prefersFreshWebSocketCandlesAndReturnsChronologicalSeries() {
        TradingProperties properties = new TradingProperties();
        OkxMarketDataWebSocketFeed feed = mock(OkxMarketDataWebSocketFeed.class);
        HotMarketDataCache hotCache = mock(HotMarketDataCache.class);
        when(feed.recentOneMinuteCandles(3)).thenReturn(List.of(
                candle("3000", "103"),
                candle("1000", "101"),
                candle("2000", "102")
        ));
        TradingMarketDataQueryService service = new TradingMarketDataQueryService(
                mock(OkxApi.class),
                properties,
                feed,
                hotCache,
                mock(TradingEventPublisher.class)
        );

        CandleSeries result = service.recentCandles("BTC-USDT", "1m", 3);

        assertEquals("WEBSOCKET", result.source());
        assertEquals(List.of("1000", "2000", "3000"),
                result.candles().stream().map(CandleResp::getTs).toList());
        verify(hotCache, never()).recentCandles("BTC-USDT", "1m", 3);
    }

    private static CandleResp candle(String timestamp, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(timestamp);
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        return candle;
    }
}
