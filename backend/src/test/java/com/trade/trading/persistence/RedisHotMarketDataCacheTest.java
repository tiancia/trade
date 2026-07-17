package com.trade.trading.persistence;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookLevel;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.HotMarketDataCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisHotMarketDataCacheTest {
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private TradingProperties properties;
    private RedisHotMarketDataCache cache;

    @BeforeEach
    void setUp() {
        properties = new TradingProperties();
        properties.getHotMarketDataCache().setKeyPrefix("test:market");
        properties.getHotMarketDataCache().setSnapshotTtlMs(60_000L);
        cache = new RedisHotMarketDataCache(redisTemplate, properties, new SimpleMeterRegistry());
    }

    @Test
    void writesAndReadsTickerAndOrderBookWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setLast("50000");
        OrderBookLevel ask = new OrderBookLevel(List.of("50001", "2", "0", "1"));
        OrderBookResp orderBook = new OrderBookResp();
        orderBook.setAsks(List.of(ask));
        orderBook.setBids(List.of());
        orderBook.setTs("1710000000000");

        assertEquals(HotMarketDataCache.CacheWriteResult.CACHED,
                cache.putSnapshot("BTC-USDT", ticker, orderBook));

        ArgumentCaptor<String> tickerJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> orderBookJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("test:market:ticker:BTC-USDT"),
                tickerJson.capture(),
                eq(Duration.ofSeconds(60))
        );
        verify(valueOperations).set(
                eq("test:market:order-book:BTC-USDT"),
                orderBookJson.capture(),
                eq(Duration.ofSeconds(60))
        );

        when(valueOperations.get("test:market:ticker:BTC-USDT")).thenReturn(tickerJson.getValue());
        when(valueOperations.get("test:market:order-book:BTC-USDT")).thenReturn(orderBookJson.getValue());

        assertEquals("50000", cache.latestTicker("BTC-USDT").orElseThrow().getLast());
        OrderBookResp cachedBook = cache.latestOrderBook("BTC-USDT").orElseThrow();
        assertEquals("50001", cachedBook.getAsks().getFirst().getPx());
        assertEquals("1710000000000", cachedBook.getTs());
    }

    @Test
    void upsertsBoundedCandlesAndReturnsNewestFirst() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.size("test:market:candles:BTC-USDT:1m")).thenReturn(2L);
        CandleResp older = candle("1710000000000", "49000");
        CandleResp newer = candle("1710000060000", "50000");

        assertEquals(HotMarketDataCache.CacheWriteResult.CACHED,
                cache.putCandles("BTC-USDT", "1M", List.of(older, newer)));

        ArgumentCaptor<String> members = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations).add(eq("test:market:candles:BTC-USDT:1m"), members.capture(),
                eq(1710000000000D));
        verify(zSetOperations).add(eq("test:market:candles:BTC-USDT:1m"), members.capture(),
                eq(1710000060000D));
        verify(redisTemplate).expire(
                "test:market:candles:BTC-USDT:1m",
                Duration.ofMillis(properties.getHotMarketDataCache().getCandleTtlMs())
        );

        List<String> encoded = members.getAllValues();
        when(zSetOperations.reverseRange("test:market:candles:BTC-USDT:1m", 0, 1))
                .thenReturn(new LinkedHashSet<>(List.of(encoded.get(1), encoded.get(0))));

        List<CandleResp> cached = cache.recentCandles("BTC-USDT", "1m", 2);
        assertEquals(List.of("1710000060000", "1710000000000"),
                cached.stream().map(CandleResp::getTs).toList());
        assertEquals("50000", cached.getFirst().getClose());
    }

    @Test
    void redisFailureDegradesToMissAndDefersImmediateRetry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(valueOperations).set(eq("test:market:ticker:BTC-USDT"), any(String.class), any(Duration.class));
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");

        assertEquals(HotMarketDataCache.CacheWriteResult.FAILED,
                cache.putSnapshot("BTC-USDT", ticker, null));
        assertTrue(cache.latestTicker("BTC-USDT").isEmpty());
        assertEquals(HotMarketDataCache.CacheWriteResult.SKIPPED,
                cache.putSnapshot("BTC-USDT", ticker, null));
    }

    private static CandleResp candle(String ts, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(ts);
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        candle.setVol("1");
        candle.setVolCcy("1");
        candle.setVolCcyQuote("50000");
        candle.setConfirm("1");
        return candle;
    }
}
