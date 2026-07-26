package com.trade.trading.scheduler;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.TradingStrategyEngine;
import com.trade.trading.application.OrderReconciliationService;
import com.trade.trading.application.TradingLeadershipService;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.HotMarketDataCache;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.market.TradingEventDetector;
import com.trade.trading.model.TradingState;
import com.trade.trading.persistence.TradingStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class TradingSchedulerTest {

    @Test
    void eventScanUsesRedisWhenProcessLocalWebSocketCacheIsEmpty() {
        TradingStrategyEngine tradingEngine = mock(TradingStrategyEngine.class);
        MarketContextCollector collector = mock(MarketContextCollector.class);
        OkxMarketDataWebSocketFeed webSocketFeed = mock(OkxMarketDataWebSocketFeed.class);
        HotMarketDataCache hotCache = mock(HotMarketDataCache.class);
        TradingEventDetector detector = mock(TradingEventDetector.class);
        TradingStateRepository stateRepository = mock(TradingStateRepository.class);
        TradingProperties properties = new TradingProperties();
        properties.setEnabled(true);
        properties.setOneMinuteCandleLimit(25);
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");
        List<CandleResp> candles = confirmedCandles(21);
        TradingState state = new TradingState();
        TradingLeadershipService leadershipService = mock(TradingLeadershipService.class);
        when(leadershipService.runIfLeader(anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });

        when(webSocketFeed.recentOneMinuteCandles(25)).thenReturn(List.of());
        when(webSocketFeed.latestTicker()).thenReturn(Optional.empty());
        when(hotCache.recentCandles("BTC-USDT", "1m", 25)).thenReturn(candles);
        when(hotCache.latestTicker("BTC-USDT")).thenReturn(Optional.of(ticker));
        when(stateRepository.getState()).thenReturn(state);
        when(detector.detect(ticker, candles, state)).thenReturn(List.of());

        new TradingScheduler(
                tradingEngine,
                mock(OrderReconciliationService.class),
                leadershipService,
                collector,
                webSocketFeed,
                hotCache,
                detector,
                stateRepository,
                properties
        ).scanEventTriggers();

        verify(detector).detect(ticker, candles, state);
        verify(collector, never()).getTicker();
        verify(collector, never()).getOneMinuteCandles();
    }

    private static List<CandleResp> confirmedCandles(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    CandleResp candle = new CandleResp();
                    candle.setTs(String.valueOf(1710000000000L - index * 60_000L));
                    candle.setClose("50000");
                    candle.setVolCcyQuote("1");
                    candle.setConfirm("1");
                    return candle;
                })
                .toList();
    }
}
