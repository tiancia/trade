package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.persistence.OkxMarketDataStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMarketDataWebSocketFeedTest {

    @Test
    void startSubscribesTickerAndOneMinuteCandles() {
        FakeOkxApi okxApi = new FakeOkxApi();
        TradingProperties properties = new TradingProperties();
        properties.setInstId("ETH-USDT");

        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(
                okxApi,
                properties,
                new CapturingMarketDataStore(),
                new SimpleMeterRegistry()
        );

        feed.start();

        assertEquals("ETH-USDT", okxApi.tickerReq.getInstId());
        assertEquals("tickers", okxApi.tickerReq.getChannel());
        assertEquals("ETH-USDT", okxApi.candleReq.getInstId());
        assertEquals("candle1m", okxApi.candleReq.getChannel());
        feed.stop();
    }

    @Test
    void cachesLatestTickerAndRecentCandles() {
        TradingProperties properties = new TradingProperties();
        properties.getWebsocket().setCandleCacheLimit(2);
        CapturingMarketDataStore marketDataStore = new CapturingMarketDataStore();
        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(
                new FakeOkxApi(),
                properties,
                marketDataStore,
                new SimpleMeterRegistry()
        );

        feed.start();

        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setLast("50000");

        feed.handleTickerData(event(List.of(ticker)));
        feed.handleCandleData(event(List.of(
                candle("1000", "49000"),
                candle("2000", "50000"),
                candle("1500", "49500")
        )));

        assertTrue(feed.latestTicker().isPresent());
        assertEquals("50000", feed.latestTicker().orElseThrow().getLast());

        List<CandleResp> candles = feed.recentOneMinuteCandles(10);
        assertEquals(2, candles.size());
        assertEquals("2000", candles.get(0).getTs());
        assertEquals("1500", candles.get(1).getTs());
        await(() -> marketDataStore.snapshotCount == 1 && marketDataStore.candleCount == 3);
        assertEquals(1, marketDataStore.snapshotCount);
        assertEquals(3, marketDataStore.candleCount);
        assertEquals("BTC-USDT", marketDataStore.lastInstId);
        feed.stop();
    }

    @Test
    void callbackRemainsNonBlockingAndDropsOldestWhenQueueIsFull() throws InterruptedException {
        TradingProperties properties = new TradingProperties();
        properties.getWebsocket().setPersistenceQueueCapacity(1);
        properties.getWebsocket().setPersistenceQueueFullPolicy(
                TradingProperties.PersistenceQueueFullPolicy.DROP_OLDEST
        );
        BlockingMarketDataStore store = new BlockingMarketDataStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(
                new FakeOkxApi(), properties, store, registry
        );

        feed.start();
        try {
            feed.handleTickerData(event(List.of(ticker("1"))));
            assertTrue(store.firstWriteStarted.await(1, TimeUnit.SECONDS));

            assertTimeout(Duration.ofMillis(500), () -> {
                feed.handleTickerData(event(List.of(ticker("2"))));
                feed.handleTickerData(event(List.of(ticker("3"))));
            });

            store.releaseWrites.countDown();
            await(() -> store.persistedPrices.size() == 2);
            assertEquals(List.of("1", "3"), store.persistedPrices);
            assertEquals(1.0, commandCount(registry, "dropped"));
            assertEquals(2.0, commandCount(registry, "persisted"));
        } finally {
            store.releaseWrites.countDown();
            feed.stop();
        }
    }

    @Test
    void persistenceFailureIsIsolatedFromFollowingCommands() {
        TradingProperties properties = new TradingProperties();
        FailsOnceMarketDataStore store = new FailsOnceMarketDataStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(
                new FakeOkxApi(), properties, store, registry
        );

        feed.start();
        try {
            feed.handleTickerData(event(List.of(ticker("1"))));
            feed.handleTickerData(event(List.of(ticker("2"))));

            await(() -> store.attempts.get() == 2);
            assertEquals(List.of("2"), store.persistedPrices);
            assertEquals(1.0, commandCount(registry, "failed"));
            assertEquals(1.0, commandCount(registry, "persisted"));
        } finally {
            feed.stop();
        }
    }

    @Test
    void stopClosesSubscriptionsAndDrainsQueuedCommands() throws InterruptedException {
        TradingProperties properties = new TradingProperties();
        properties.getWebsocket().setPersistenceQueueCapacity(4);
        properties.getWebsocket().setPersistenceShutdownTimeoutMs(2_000L);
        BlockingMarketDataStore store = new BlockingMarketDataStore();
        FakeOkxApi okxApi = new FakeOkxApi();
        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(
                okxApi, properties, store, new SimpleMeterRegistry()
        );

        feed.start();
        feed.handleTickerData(event(List.of(ticker("1"))));
        assertTrue(store.firstWriteStarted.await(1, TimeUnit.SECONDS));
        feed.handleTickerData(event(List.of(ticker("2"))));

        Thread stopper = new Thread(feed::stop);
        stopper.start();
        try {
            await(() -> okxApi.closedSubscriptions.get() == 2);
            assertTrue(stopper.isAlive(), "stop should wait for the in-flight database write");
            store.releaseWrites.countDown();
            stopper.join(2_000L);

            assertFalse(stopper.isAlive());
            assertEquals(List.of("1", "2"), store.persistedPrices);
        } finally {
            store.releaseWrites.countDown();
            stopper.join(2_000L);
            feed.stop();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static double commandCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get("trade.okx.market.persistence.commands")
                .tags("instrument", "BTC-USDT", "outcome", outcome)
                .counter()
                .count();
    }

    private static TickerResp ticker(String last) {
        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setLast(last);
        return ticker;
    }

    private static CandleResp candle(String ts, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(ts);
        candle.setClose(close);
        candle.setConfirm("1");
        return candle;
    }

    private static <T> OkxWsEvent<T> event(List<T> data) {
        OkxWsEvent<T> event = new OkxWsEvent<>();
        event.setData(data);
        return event;
    }

    private static class FakeOkxApi extends OkxApi {
        private TickerChannelReq tickerReq;
        private CandleChannelReq candleReq;
        private final AtomicInteger closedSubscriptions = new AtomicInteger();

        FakeOkxApi() {
            super(new NoopOkxRestClient());
        }

        @Override
        public OkxWsSubscription subscribeTicker(TickerChannelReq req, OkxWsListener<TickerResp> listener) {
            this.tickerReq = req;
            return new NoopSubscription(closedSubscriptions);
        }

        @Override
        public OkxWsSubscription subscribeCandles(CandleChannelReq req, OkxWsListener<CandleResp> listener) {
            this.candleReq = req;
            return new NoopSubscription(closedSubscriptions);
        }
    }

    private static class NoopSubscription implements OkxWsSubscription {
        private final AtomicInteger closedSubscriptions;

        private NoopSubscription(AtomicInteger closedSubscriptions) {
            this.closedSubscriptions = closedSubscriptions;
        }

        @Override
        public void unsubscribe() {
        }

        @Override
        public void close() {
            closedSubscriptions.incrementAndGet();
        }
    }

    private static class NoopOkxRestClient implements OkxRestClient {
        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }
    }

    private static class CapturingMarketDataStore implements OkxMarketDataStore {
        private volatile int snapshotCount;
        private volatile int candleCount;
        private volatile String lastInstId;

        @Override
        public void saveSnapshot(String instId, String source, TickerResp ticker, com.trade.client.okx.dto.OrderBookResp orderBook) {
            snapshotCount++;
            lastInstId = instId;
        }

        @Override
        public void saveCandles(String instId, String bar, List<CandleResp> candles) {
            candleCount += candles == null ? 0 : candles.size();
            lastInstId = instId;
        }
    }

    private static class BlockingMarketDataStore implements OkxMarketDataStore {
        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrites = new CountDownLatch(1);
        private final List<String> persistedPrices = new CopyOnWriteArrayList<>();

        @Override
        public void saveSnapshot(String instId, String source, TickerResp ticker,
                                 com.trade.client.okx.dto.OrderBookResp orderBook) {
            firstWriteStarted.countDown();
            try {
                releaseWrites.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("write interrupted", e);
            }
            persistedPrices.add(ticker.getLast());
        }

        @Override
        public void saveCandles(String instId, String bar, List<CandleResp> candles) {
        }
    }

    private static class FailsOnceMarketDataStore implements OkxMarketDataStore {
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> persistedPrices = new CopyOnWriteArrayList<>();

        @Override
        public void saveSnapshot(String instId, String source, TickerResp ticker,
                                 com.trade.client.okx.dto.OrderBookResp orderBook) {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            persistedPrices.add(ticker.getLast());
        }

        @Override
        public void saveCandles(String instId, String bar, List<CandleResp> candles) {
        }
    }
}
