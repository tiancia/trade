package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.config.AiTradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OkxMarketDataWebSocketFeed implements ApplicationRunner, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OkxMarketDataWebSocketFeed.class);

    private final OkxApi okxApi;
    private final AiTradingProperties properties;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<TickerResp> latestTicker = new AtomicReference<>();
    private final AtomicReference<Instant> latestTickerAt = new AtomicReference<>();
    private final AtomicReference<Instant> latestCandleAt = new AtomicReference<>();
    private final List<OkxWsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, CandleResp> oneMinuteCandles = new LinkedHashMap<>();

    public OkxMarketDataWebSocketFeed(OkxApi okxApi, AiTradingProperties properties) {
        this.okxApi = okxApi;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    public void start() {
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
        for (OkxWsSubscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.warn("Close OKX market data WebSocket subscription failed", e);
            }
        }
        subscriptions.clear();
        started.set(false);
    }

    void handleTickerData(OkxWsEvent<TickerResp> event) {
        if (event == null || event.getData() == null || event.getData().isEmpty()) {
            return;
        }

        TickerResp ticker = event.getData().getFirst();
        latestTicker.set(ticker);
        latestTickerAt.set(Instant.now());
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
        return Duration.between(updateTime, Instant.now()).toMillis() > properties.getWebsocket().getStaleTimeoutMs();
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
