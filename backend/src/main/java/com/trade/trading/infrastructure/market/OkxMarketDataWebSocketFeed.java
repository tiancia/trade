package com.trade.trading.infrastructure.market;

import com.trade.trading.port.TradingMarketFeed;
import com.trade.trading.application.market.CandleFreshness;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventSource;
import com.trade.trading.port.TradingEventPublisher;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns OKX public WebSocket subscriptions and an in-memory latest-value cache.
 * Database work is delegated to the bounded trading event pipeline.
 */
@Component
public class OkxMarketDataWebSocketFeed implements TradingMarketFeed, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OkxMarketDataWebSocketFeed.class);
    private static final List<String> CANDLE_BARS = List.of("1m", "5m", "15m", "1H", "4H");

    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final TradingEventPublisher eventPublisher;
    private final Clock clock;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<TickerResp> latestTicker = new AtomicReference<>();
    private final AtomicReference<Instant> latestTickerAt = new AtomicReference<>();
    private final List<OkxWsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, CandleResp>> candlesByBar = new LinkedHashMap<>();
    private final Map<String, Instant> latestCandleAtByBar = new LinkedHashMap<>();

    @Autowired
    public OkxMarketDataWebSocketFeed(
            OkxApi okxApi,
            TradingProperties properties,
            TradingEventPublisher eventPublisher
    ) {
        this(okxApi, properties, eventPublisher, Clock.systemUTC());
    }

    OkxMarketDataWebSocketFeed(
            OkxApi okxApi,
            TradingProperties properties,
            TradingEventPublisher eventPublisher,
            Clock clock
    ) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
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
        subscribeCandles();
    }

    public synchronized void stop() {
        started.set(false);
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
        latestCandleAtByBar.clear();
        candlesByBar.clear();
    }

    public Optional<TickerResp> latestTicker() {
        TickerResp ticker = latestTicker.get();
        if (ticker == null || isStale(latestTickerAt.get()) || !validTicker(ticker)) {
            return Optional.empty();
        }
        return Optional.of(ticker);
    }

    public List<CandleResp> recentOneMinuteCandles(int limit) {
        return recentCandles("1m", limit);
    }

    @Override
    public synchronized List<CandleResp> recentCandles(String requestedBar, int limit) {
        String bar = candleBar(requestedBar);
        if (bar == null || limit <= 0 || isStale(latestCandleAtByBar.get(bar))) {
            return List.of();
        }
        List<CandleResp> candles = candlesByBar.getOrDefault(bar, Map.of()).values().stream()
                .sorted(Comparator.comparing(OkxMarketDataWebSocketFeed::candleTimestamp).reversed())
                .limit(limit)
                .toList();
        return CandleFreshness.isCurrent(candles, bar, clock.instant()) ? candles : List.of();
    }

    @Override
    public void destroy() {
        stop();
    }

    synchronized void handleTickerData(OkxWsEvent<TickerResp> event) {
        if (!started.get() || event == null || event.getData() == null || event.getData().isEmpty()) {
            return;
        }

        TickerResp ticker = event.getData().stream()
                .filter(this::validTicker)
                .max(Comparator.comparingLong(value -> timestamp(value.getTs())))
                .orElse(null);
        TickerResp previous = latestTicker.get();
        if (ticker == null || previous != null && timestamp(ticker.getTs()) < timestamp(previous.getTs())) {
            return;
        }
        latestTicker.set(ticker);
        latestTickerAt.set(clock.instant());
        eventPublisher.publish(TradingEvent.marketSnapshot(
                properties.getInstId(),
                TradingEventSource.OKX_WEBSOCKET,
                null,
                ticker,
                null
        ));
    }

    void handleCandleData(OkxWsEvent<CandleResp> event) {
        handleCandleData("1m", event);
    }

    private synchronized void handleCandleData(String bar, OkxWsEvent<CandleResp> event) {
        if (!started.get() || event == null || event.getData() == null || event.getData().isEmpty()) {
            return;
        }

        List<CandleResp> updates = event.getData().stream()
                .filter(candle -> candleTimestamp(candle) > 0L
                        && candleTimestamp(candle) <= clock.millis()
                        && positivePrice(candle.getClose()))
                .toList();
        if (updates.isEmpty()) {
            return;
        }
        Map<String, CandleResp> candles = candlesByBar.computeIfAbsent(bar, ignored -> new LinkedHashMap<>());
        for (CandleResp candle : updates) {
            candles.put(candle.getTs(), candle);
        }
        trimCandleCache(candles);
        if (CandleFreshness.isCurrent(updates, bar, clock.instant())) {
            latestCandleAtByBar.put(bar, clock.instant());
        }
        eventPublisher.publish(TradingEvent.candleBatch(
                properties.getInstId(),
                TradingEventSource.OKX_WEBSOCKET,
                null,
                bar,
                updates
        ));
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

    private void subscribeCandles() {
        try {
            OkxWsSubscription subscription = okxApi.subscribeCandles(
                    CANDLE_BARS.stream()
                            .map(bar -> new CandleChannelReq()
                                    .setChannel("candle" + bar)
                                    .setInstId(properties.getInstId()))
                            .toList(),
                    new OkxWsListener<>() {
                        @Override
                        public void onEvent(OkxWsEvent<CandleResp> event) {
                            log.info("OKX candle WebSocket event: {}", event);
                        }

                        @Override
                        public void onData(OkxWsEvent<CandleResp> event) {
                            if (event == null || event.getArg() == null
                                    || !properties.getInstId().equalsIgnoreCase(event.getArg().getInstId())) {
                                return;
                            }
                            String channel = event.getArg().getChannel();
                            String bar = channel != null && channel.startsWith("candle")
                                    ? candleBar(channel.substring("candle".length())) : null;
                            if (bar != null) {
                                handleCandleData(bar, event);
                            }
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
            log.info("Subscribed OKX candle WebSocket: instId={}, bars={}", properties.getInstId(), CANDLE_BARS);
        } catch (Exception e) {
            log.warn("Subscribe OKX candle WebSocket failed", e);
        }
    }

    private boolean isStale(Instant updateTime) {
        if (updateTime == null) {
            return true;
        }
        long age = clock.millis() - updateTime.toEpochMilli();
        return age < 0L || age > properties.getWebsocket().getStaleTimeoutMs();
    }

    private void trimCandleCache(Map<String, CandleResp> candles) {
        int maxSize = Math.max(properties.getWebsocket().getCandleCacheLimit(), 0);
        if (candles.size() <= maxSize) {
            return;
        }
        List<String> keysToKeep = candles.values().stream()
                .sorted(Comparator.comparing(OkxMarketDataWebSocketFeed::candleTimestamp).reversed())
                .limit(maxSize)
                .map(CandleResp::getTs)
                .toList();
        candles.keySet().retainAll(keysToKeep);
    }

    private static long candleTimestamp(CandleResp candle) {
        return candle == null ? 0L : timestamp(candle.getTs());
    }

    private static long timestamp(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private boolean validTicker(TickerResp ticker) {
        if (ticker == null || !properties.getInstId().equalsIgnoreCase(ticker.getInstId())
                || !positivePrice(ticker.getLast()) || timestamp(ticker.getTs()) <= 0L) {
            return false;
        }
        return !isStale(Instant.ofEpochMilli(timestamp(ticker.getTs())));
    }

    private static boolean positivePrice(String value) {
        try {
            return value != null && new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String candleBar(String requested) {
        return CANDLE_BARS.stream()
                .filter(bar -> bar.equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }
}
