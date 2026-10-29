package com.trade.trading.application.runtime;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.market.TradingEventDetector;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.port.HotMarketDataCache;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.TradingMarketSource;
import com.trade.trading.port.TradingMarketFeed;
import com.trade.trading.port.TradingStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingTriggerServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T08:04:20Z"), ZoneOffset.UTC);

    @Test void scheduledAndEventFailuresReachTheAutomationCaller() {
        var engine = mock(TradingStrategyEngine.class);
        when(engine.isIntradayStrategyActive()).thenReturn(true);
        when(engine.runDecision(any())).thenThrow(new IllegalStateException("decision failed"));
        var leadership = mock(TradingLeadershipService.class);
        when(leadership.runIfLeader(anyString(), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run(); return true;
        });
        var service = new TradingTriggerService(engine, mock(OrderReconciliationService.class), leadership,
                mock(TradingMarketSource.class), mock(TradingMarketFeed.class), mock(HotMarketDataCache.class),
                mock(TradingEventDetector.class), mock(TradingStateStore.class), new TradingProperties(),
                mock(PaperAccountService.class), CLOCK);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::runScheduledDecision);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::scanEventTriggers);
    }

    @Test
    void intradayScanEvaluatesWithoutLegacyOneMinuteMarketEventOrCooldown() {
        TradingStrategyEngine engine = mock(TradingStrategyEngine.class);
        when(engine.isIntradayStrategyActive()).thenReturn(true);
        TradingLeadershipService leadership = mock(TradingLeadershipService.class);
        when(leadership.runIfLeader(anyString(), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run(); return true;
        });
        TradingEventDetector detector = mock(TradingEventDetector.class);
        TradingMarketSource source = mock(TradingMarketSource.class);
        TradingTriggerService service = new TradingTriggerService(engine, mock(OrderReconciliationService.class),
                leadership, source, mock(TradingMarketFeed.class), mock(HotMarketDataCache.class), detector,
                mock(TradingStateStore.class), new TradingProperties(), mock(PaperAccountService.class), CLOCK);
        service.scanEventTriggers();
        service.scanEventTriggers();
        verify(engine, org.mockito.Mockito.times(2)).runDecision(any());
        org.mockito.Mockito.verifyNoInteractions(detector, source);
    }

    @Test
    void eventScanUsesRedisWhenProcessLocalWebSocketCacheIsEmpty() {
        TradingStrategyEngine tradingEngine = mock(TradingStrategyEngine.class);
        TradingMarketSource collector = mock(TradingMarketSource.class);
        TradingMarketFeed webSocketFeed = mock(TradingMarketFeed.class);
        HotMarketDataCache hotCache = mock(HotMarketDataCache.class);
        TradingEventDetector detector = mock(TradingEventDetector.class);
        TradingStateStore stateRepository = mock(TradingStateStore.class);
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

        new TradingTriggerService(
                tradingEngine,
                mock(OrderReconciliationService.class),
                leadershipService,
                collector,
                webSocketFeed,
                hotCache,
                detector,
                stateRepository,
                properties,
                mock(PaperAccountService.class),
                CLOCK
        ).scanEventTriggers();

        verify(detector).detect(ticker, candles, state);
        verify(collector, never()).getTicker();
        verify(collector, never()).getOneMinuteCandles();
    }

    @Test
    void eventScanFetchesRestWhenRedisHasEnoughButOutdatedConfirmedCandles() {
        TradingMarketSource collector = mock(TradingMarketSource.class);
        TradingMarketFeed feed = mock(TradingMarketFeed.class);
        HotMarketDataCache cache = mock(HotMarketDataCache.class);
        TradingEventDetector detector = mock(TradingEventDetector.class);
        TradingStateStore stateStore = mock(TradingStateStore.class);
        TradingProperties properties = new TradingProperties();
        properties.setOneMinuteCandleLimit(25);
        TradingLeadershipService leadership = mock(TradingLeadershipService.class);
        when(leadership.runIfLeader(anyString(), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        List<CandleResp> stale = confirmedCandles(25).stream().map(candle -> {
            candle.setTs(String.valueOf(Long.parseLong(candle.getTs()) - 300_000L));
            return candle;
        }).toList();
        List<CandleResp> fresh = confirmedCandles(25);
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");
        TradingState state = new TradingState();
        when(cache.recentCandles("BTC-USDT", "1m", 25)).thenReturn(stale);
        when(cache.latestTicker("BTC-USDT")).thenReturn(Optional.of(ticker));
        when(collector.getOneMinuteCandles()).thenReturn(fresh);
        when(stateStore.getState()).thenReturn(state);
        when(detector.detect(ticker, fresh, state)).thenReturn(List.of());

        new TradingTriggerService(mock(TradingStrategyEngine.class), mock(OrderReconciliationService.class),
                leadership, collector, feed, cache, detector, stateStore, properties,
                mock(PaperAccountService.class), CLOCK).scanEventTriggers();

        verify(collector).getOneMinuteCandles();
        verify(detector).detect(ticker, fresh, state);
    }

    private static List<CandleResp> confirmedCandles(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    CandleResp candle = new CandleResp();
                    candle.setTs(String.valueOf(CLOCK.millis() / 60_000L * 60_000L - index * 60_000L));
                    candle.setClose("50000");
                    candle.setVolCcyQuote("1");
                    candle.setConfirm("1");
                    return candle;
                })
                .toList();
    }
}
