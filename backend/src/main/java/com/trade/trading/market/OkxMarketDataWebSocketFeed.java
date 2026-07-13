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
import com.trade.trading.persistence.OkxMarketDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OkxMarketDataWebSocketFeed implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OkxMarketDataWebSocketFeed.class);

    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final OkxMarketDataStore marketDataStore;
    private final MeterRegistry meterRegistry;
    private final BlockingQueue<PersistenceCommand> persistenceQueue;
    private final Counter enqueuedCounter;
    private final Counter consumedCounter;
    private final Counter persistedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter droppedCounter;
    private final Timer persistenceTimer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean acceptingPersistence = new AtomicBoolean(false);
    private final AtomicReference<Thread> persistenceWorker = new AtomicReference<>();
    private final AtomicLong droppedCommands = new AtomicLong();
    private final AtomicReference<TickerResp> latestTicker = new AtomicReference<>();
    private final AtomicReference<Instant> latestTickerAt = new AtomicReference<>();
    private final AtomicReference<Instant> latestCandleAt = new AtomicReference<>();
    private final List<OkxWsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, CandleResp> oneMinuteCandles = new LinkedHashMap<>();

    public OkxMarketDataWebSocketFeed(
            OkxApi okxApi,
            TradingProperties properties,
            OkxMarketDataStore marketDataStore,
            MeterRegistry meterRegistry
    ) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.marketDataStore = marketDataStore;
        this.meterRegistry = meterRegistry;
        int queueCapacity = Math.max(properties.getWebsocket().getPersistenceQueueCapacity(), 1);
        this.persistenceQueue = new LinkedBlockingQueue<>(queueCapacity);

        String instrument = properties.getInstId();
        this.enqueuedCounter = commandCounter(meterRegistry, instrument, "enqueued");
        this.consumedCounter = commandCounter(meterRegistry, instrument, "consumed");
        this.persistedCounter = commandCounter(meterRegistry, instrument, "persisted");
        this.skippedCounter = commandCounter(meterRegistry, instrument, "skipped");
        this.failedCounter = commandCounter(meterRegistry, instrument, "failed");
        this.droppedCounter = commandCounter(meterRegistry, instrument, "dropped");
        this.persistenceTimer = Timer.builder("trade.okx.market.persistence.duration")
                .description("Time spent persisting one queued OKX market data command")
                .tag("instrument", instrument)
                .register(meterRegistry);
        Gauge.builder("trade.okx.market.persistence.queue.depth", persistenceQueue, BlockingQueue::size)
                .description("Pending OKX market data persistence commands")
                .tag("instrument", instrument)
                .register(meterRegistry);
        Gauge.builder("trade.okx.market.persistence.queue.capacity", persistenceQueue,
                        queue -> queue.size() + queue.remainingCapacity())
                .description("Configured OKX market data persistence queue capacity")
                .tag("instrument", instrument)
                .register(meterRegistry);
    }

    public synchronized void start() {
        if (!properties.isEnabled() || !properties.getWebsocket().isEnabled()) {
            log.info("OKX market data WebSocket is disabled");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Thread existingWorker = persistenceWorker.get();
        if (existingWorker != null && existingWorker.isAlive()) {
            started.set(false);
            log.warn("Cannot start OKX market data WebSocket while the previous persistence worker is still stopping");
            return;
        }

        persistenceQueue.clear();
        acceptingPersistence.set(true);
        startPersistenceWorker();

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
        acceptingPersistence.set(false);
        drainPersistenceQueueOnShutdown();
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
        enqueuePersistence(new TickerPersistenceCommand(
                properties.getInstId(),
                OkxMarketDataStore.SOURCE_WEBSOCKET_TICKER,
                ticker
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
        if (persistableUpdates.isEmpty()) {
            return;
        }
        enqueuePersistence(new CandlePersistenceCommand(
                properties.getInstId(),
                "1m",
                persistableUpdates
        ));
    }

    private void startPersistenceWorker() {
        Thread worker = new Thread(this::consumePersistenceCommands,
                "okx-market-persistence-" + properties.getInstId().replaceAll("[^A-Za-z0-9_-]", "-"));
        worker.setDaemon(true);
        persistenceWorker.set(worker);
        worker.start();
        log.info("Started OKX market persistence worker: instId={}, queueCapacity={}, fullPolicy={}",
                properties.getInstId(),
                persistenceQueue.size() + persistenceQueue.remainingCapacity(),
                properties.getWebsocket().getPersistenceQueueFullPolicy());
    }

    private void enqueuePersistence(PersistenceCommand command) {
        if (!acceptingPersistence.get()) {
            recordDropped(1, "worker-not-accepting");
            return;
        }
        if (offerPersistenceCommand(command)) {
            return;
        }

        if (properties.getWebsocket().getPersistenceQueueFullPolicy()
                == TradingProperties.PersistenceQueueFullPolicy.DROP_LATEST) {
            recordDropped(1, "queue-full-drop-latest");
            return;
        }

        PersistenceCommand discarded = persistenceQueue.poll();
        if (discarded != null) {
            recordDropped(1, "queue-full-drop-oldest");
        }
        if (!offerPersistenceCommand(command)) {
            recordDropped(1, "queue-full-race");
        }
    }

    private boolean offerPersistenceCommand(PersistenceCommand command) {
        if (!persistenceQueue.offer(command)) {
            return false;
        }
        // Close the race where shutdown starts after the initial accepting check
        // but before this offer. If the consumer already took the command, it is
        // part of the normal drain and cannot be removed here.
        if (!acceptingPersistence.get() && persistenceQueue.remove(command)) {
            recordDropped(1, "worker-stopping");
            return true;
        }
        enqueuedCounter.increment();
        return true;
    }

    private void consumePersistenceCommands() {
        Thread current = Thread.currentThread();
        try {
            while (acceptingPersistence.get() || !persistenceQueue.isEmpty()) {
                try {
                    PersistenceCommand command = persistenceQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        persist(command);
                    }
                } catch (InterruptedException e) {
                    if (acceptingPersistence.get() || !persistenceQueue.isEmpty()) {
                        log.debug("OKX market persistence worker interrupted while commands remain");
                    }
                }
            }
        } finally {
            persistenceWorker.compareAndSet(current, null);
            log.info("Stopped OKX market persistence worker: instId={}, remainingQueueDepth={}",
                    properties.getInstId(), persistenceQueue.size());
        }
    }

    private void persist(PersistenceCommand command) {
        consumedCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OkxMarketDataStore.SaveResult result = command.persist(marketDataStore);
            if (result == OkxMarketDataStore.SaveResult.SAVED) {
                persistedCounter.increment();
            } else if (result == OkxMarketDataStore.SaveResult.SKIPPED) {
                skippedCounter.increment();
            } else {
                failedCounter.increment();
            }
        } catch (Exception e) {
            failedCounter.increment();
            log.warn("Persist queued OKX market data failed: instId={}, commandType={}",
                    properties.getInstId(), command.type(), e);
        } finally {
            sample.stop(persistenceTimer);
        }
    }

    private void drainPersistenceQueueOnShutdown() {
        Thread worker = persistenceWorker.get();
        if (worker == null || worker == Thread.currentThread()) {
            return;
        }

        long timeoutMs = Math.max(properties.getWebsocket().getPersistenceShutdownTimeoutMs(), 0L);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean interrupted = false;
        while (worker.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos);
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        if (worker.isAlive()) {
            int abandoned = persistenceQueue.size();
            persistenceQueue.clear();
            recordDropped(abandoned, "shutdown-timeout");
            worker.interrupt();
            log.warn("Timed out draining OKX market persistence queue: instId={}, timeoutMs={}, abandoned={}",
                    properties.getInstId(), timeoutMs, abandoned);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordDropped(long count, String reason) {
        if (count <= 0) {
            return;
        }
        droppedCounter.increment(count);
        long total = droppedCommands.addAndGet(count);
        if (total == count || total % 100 == 0) {
            log.warn("Dropped OKX market persistence commands: instId={}, reason={}, count={}, totalDropped={}, queueDepth={}",
                    properties.getInstId(), reason, count, total, persistenceQueue.size());
        }
    }

    private static Counter commandCounter(MeterRegistry registry, String instrument, String outcome) {
        return Counter.builder("trade.okx.market.persistence.commands")
                .description("OKX market data persistence command outcomes")
                .tag("instrument", instrument)
                .tag("outcome", outcome)
                .register(registry);
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

    private interface PersistenceCommand {
        OkxMarketDataStore.SaveResult persist(OkxMarketDataStore store);

        String type();
    }

    private record TickerPersistenceCommand(
            String instId,
            String source,
            TickerResp ticker
    ) implements PersistenceCommand {
        @Override
        public OkxMarketDataStore.SaveResult persist(OkxMarketDataStore store) {
            return store.saveSnapshotWithResult(instId, source, ticker, null);
        }

        @Override
        public String type() {
            return "ticker";
        }
    }

    private record CandlePersistenceCommand(
            String instId,
            String bar,
            List<CandleResp> candles
    ) implements PersistenceCommand {
        @Override
        public OkxMarketDataStore.SaveResult persist(OkxMarketDataStore store) {
            return store.saveCandlesWithResult(instId, bar, candles);
        }

        @Override
        public String type() {
            return "candle";
        }
    }
}
