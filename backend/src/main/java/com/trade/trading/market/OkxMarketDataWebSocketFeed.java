package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.event.TradingEvent;
import com.trade.trading.event.TradingEventPublisher;
import com.trade.trading.event.TradingEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns OKX public WebSocket subscriptions and an in-memory latest-value cache.
 * Database work is delegated to the bounded trading event pipeline.
 */
@Component
public class OkxMarketDataWebSocketFeed implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OkxMarketDataWebSocketFeed.class);

    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final TradingEventPublisher eventPublisher;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<TickerResp> latestTicker = new AtomicReference<>();
    private final AtomicReference<Instant> latestTickerAt = new AtomicReference<>();
    private final AtomicReference<Instant> latestCandleAt = new AtomicReference<>();
    private final List<OkxWsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, CandleResp> oneMinuteCandles = new LinkedHashMap<>();

    public OkxMarketDataWebSocketFeed(
            OkxApi okxApi,
            TradingProperties properties,
            TradingEventPublisher eventPublisher
    ) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    public synchronized void start() {
        if (!properties.isEnabled() || !properties.getWebsocket().isEnabled()) {
            log.info("OKX market data WebSocket is disabled");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        subscribeTicker();
        subscribeOneMinuteCandles();
    }

    public synchronized void stop() {
        for (OkxWsSubscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.warn("Close OKX market data WebSocket subscription failed", e);
            }
        }
        subscriptions.clear();
        latestTicker.set(null);
        latestTickerAt.set(null);
        latestCandleAt.set(null);
        synchronized (oneMinuteCandles) {
            oneMinuteCandles.clear();
        }
        started.set(false);
    }

    public Optional<TickerResp> latestTicker() {
        TickerResp ticker = latestTicker.get();
        if (ticker == null || isStale(latestTickerAt.get())) {
            return Optional.empty();
        }
        return Optional.of(ticker);
    }

    public List<CandleResp> recentOneMinuteCandles(int limit) {
        if (isStale(latestCandleAt.get())) {
            return List.of();
        }
        synchronized (oneMinuteCandles) {
            return oneMinuteCandles.values().stream()
                    .sorted(Comparator.comparing(OkxMarketDataWebSocketFeed::candleTimestamp).reversed())
                    .limit(Math.max(limit, 0))
                    .toList();
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    void handleTickerData(OkxWsEvent<TickerResp> event) {
        if (event == null || event.getData() == null || event.getData().isEmpty()) {
            return;
        }

        TickerResp ticker = event.getData().getFirst();
        latestTicker.set(ticker);
        latestTickerAt.set(Instant.now());
        eventPublisher.publish(TradingEvent.marketSnapshot(
                properties.getInstId(),
                TradingEventSource.OKX_WEBSOCKET,
                null,
                ticker,
                null
        ));
    }

    void handleCandleData(OkxWsEvent<CandleResp> event) {
        if (event == null || event.getData() == null || event.getData().isEmpty()) {
            return;
        }

        List<CandleResp> updates = new ArrayList<>(event.getData());
        synchronized (oneMinuteCandles) {
            for (CandleResp candle : updates) {
                if (candle == null || candle.getTs() == null || candle.getTs().isBlank()) {
                    continue;
                }
                oneMinuteCandles.put(candle.getTs(), candle);
            }
            trimCandleCache();
        }
        latestCandleAt.set(Instant.now());
        List<CandleResp> persistableUpdates = updates.stream()
                .filter(Objects::nonNull)
                .toList();
        if (!persistableUpdates.isEmpty()) {
            eventPublisher.publish(TradingEvent.candleBatch(
                    properties.getInstId(),
                    TradingEventSource.OKX_WEBSOCKET,
                    null,
                    "1m",
                    persistableUpdates
            ));
        }
    }

    private void subscribeTicker() {
        try {
            OkxWsSubscription subscription = okxApi.subscribeTicker(
                    new TickerChannelReq().setInstId(properties.getInstId()),
                    new OkxWsListener<>() {
                        @Override
                        public void onEvent(OkxWsEvent<TickerResp> event) {
                            log.info("OKX ticker WebSocket event: {}", event);
                        }

                        @Override
                        public void onData(OkxWsEvent<TickerResp> event) {
                            handleTickerData(event);
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.warn("OKX ticker WebSocket error", error);
                        }

                        @Override
                        public void onClose(int statusCode, String reason) {
                            log.warn("OKX ticker WebSocket closed: statusCode={}, reason={}", statusCode, reason);
                        }
                    }
            );
            subscriptions.add(subscription);
            log.info("Subscribed OKX ticker WebSocket: instId={}", properties.getInstId());
        } catch (Exception e) {
            log.warn("Subscribe OKX ticker WebSocket failed", e);
        }
    }

    private void subscribeOneMinuteCandles() {
        try {
            OkxWsSubscription subscription = okxApi.subscribeCandles(
                    new CandleChannelReq().setInstId(properties.getInstId()),
                    new OkxWsListener<>() {
                        @Override
                        public void onEvent(OkxWsEvent<CandleResp> event) {
                            log.info("OKX candle WebSocket event: {}", event);
                        }

                        @Override
                        public void onData(OkxWsEvent<CandleResp> event) {
                            handleCandleData(event);
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.warn("OKX candle WebSocket error", error);
                        }

                        @Override
                        public void onClose(int statusCode, String reason) {
                            log.warn("OKX candle WebSocket closed: statusCode={}, reason={}", statusCode, reason);
                        }
                    }
            );
            subscriptions.add(subscription);
            log.info("Subscribed OKX 1m candle WebSocket: instId={}", properties.getInstId());
        } catch (Exception e) {
            log.warn("Subscribe OKX 1m candle WebSocket failed", e);
        }
    }

    private boolean isStale(Instant updateTime) {
        if (updateTime == null) {
            return true;
        }
        return Duration.between(updateTime, Instant.now()).toMillis()
                > properties.getWebsocket().getStaleTimeoutMs();
    }

    private void trimCandleCache() {
        int maxSize = Math.max(properties.getWebsocket().getCandleCacheLimit(), 0);
        if (oneMinuteCandles.size() <= maxSize) {
            return;
        }
        List<String> keysToKeep = oneMinuteCandles.values().stream()
                .sorted(Comparator.comparing(OkxMarketDataWebSocketFeed::candleTimestamp).reversed())
                .limit(maxSize)
                .map(CandleResp::getTs)
                .toList();
        oneMinuteCandles.keySet().retainAll(keysToKeep);
    }

    private static long candleTimestamp(CandleResp candle) {
        if (candle == null || candle.getTs() == null || candle.getTs().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(candle.getTs());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
