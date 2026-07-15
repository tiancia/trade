package com.trade.trading.event;

import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
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

class BoundedTradingEventBusTest {

    @Test
    void dropOldestKeepsProducerNonBlockingAndPreservesFresherData() throws InterruptedException {
        TradingProperties properties = properties(TradingProperties.EventQueueFullPolicy.DROP_OLDEST);
        BlockingHandler handler = new BlockingHandler();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BoundedTradingEventBus bus = new BoundedTradingEventBus(properties, List.of(handler), registry);
        bus.start();
        try {
            assertEquals(TradingEventPublishResult.ACCEPTED, bus.publish(event("1")));
            assertTrue(handler.firstStarted.await(1, TimeUnit.SECONDS));

            assertTimeout(Duration.ofMillis(500), () -> {
                assertEquals(TradingEventPublishResult.ACCEPTED, bus.publish(event("2")));
                assertEquals(
                        TradingEventPublishResult.ACCEPTED_AFTER_DROPPING_OLDEST,
                        bus.publish(event("3"))
                );
            });

            handler.release.countDown();
            await(() -> handler.values.size() == 2);
            assertEquals(List.of("1", "3"), handler.values);
            assertEquals(1, bus.status().dropped());
            assertEquals(1.0, registry.get("trade.trading.events.dropped")
                    .tag("reason", "queue_full_drop_oldest").counter().count());
        } finally {
            handler.release.countDown();
            bus.stop();
        }
    }

    @Test
    void dropLatestRejectsNewEventWhenQueueIsFull() throws InterruptedException {
        TradingProperties properties = properties(TradingProperties.EventQueueFullPolicy.DROP_LATEST);
        BlockingHandler handler = new BlockingHandler();
        BoundedTradingEventBus bus = new BoundedTradingEventBus(
                properties, List.of(handler), new SimpleMeterRegistry()
        );
        bus.start();
        try {
            bus.publish(event("1"));
            assertTrue(handler.firstStarted.await(1, TimeUnit.SECONDS));
            bus.publish(event("2"));
            assertEquals(TradingEventPublishResult.DROPPED_LATEST, bus.publish(event("3")));

            handler.release.countDown();
            await(() -> handler.values.size() == 2);
            assertEquals(List.of("1", "2"), handler.values);
        } finally {
            handler.release.countDown();
            bus.stop();
        }
    }

    @Test
    void blockPolicyTimesOutInsteadOfGrowingQueue() throws InterruptedException {
        TradingProperties properties = properties(TradingProperties.EventQueueFullPolicy.BLOCK);
        properties.getEventQueue().setPublishTimeoutMs(30L);
        BlockingHandler handler = new BlockingHandler();
        BoundedTradingEventBus bus = new BoundedTradingEventBus(
                properties, List.of(handler), new SimpleMeterRegistry()
        );
        bus.start();
        try {
            bus.publish(event("1"));
            assertTrue(handler.firstStarted.await(1, TimeUnit.SECONDS));
            bus.publish(event("2"));

            assertEquals(TradingEventPublishResult.TIMED_OUT, bus.publish(event("3")));
            assertEquals(1, bus.status().queueDepth());
        } finally {
            handler.release.countDown();
            bus.stop();
        }
    }

    @Test
    void handlerExceptionIsIsolatedFromFollowingEvents() {
        FailsOnceHandler handler = new FailsOnceHandler();
        TradingProperties properties = properties(TradingProperties.EventQueueFullPolicy.DROP_OLDEST);
        properties.getEventQueue().setCapacity(4);
        BoundedTradingEventBus bus = new BoundedTradingEventBus(
                properties,
                List.of(handler),
                new SimpleMeterRegistry()
        );
        bus.start();
        try {
            bus.publish(event("1"));
            bus.publish(event("2"));

            await(() -> bus.status().consumed() == 2);
            assertEquals(List.of("2"), handler.values);
            assertEquals(2, bus.status().consumed());
            assertEquals(1, bus.status().failed());
            assertTrue(bus.status().running());
        } finally {
            bus.stop();
        }
    }

    @Test
    void stopRejectsNewEventsAndDrainsAcceptedEvents() throws InterruptedException {
        TradingProperties properties = properties(TradingProperties.EventQueueFullPolicy.DROP_OLDEST);
        properties.getEventQueue().setShutdownTimeoutMs(2_000L);
        BlockingHandler handler = new BlockingHandler();
        BoundedTradingEventBus bus = new BoundedTradingEventBus(
                properties, List.of(handler), new SimpleMeterRegistry()
        );
        bus.start();
        bus.publish(event("1"));
        assertTrue(handler.firstStarted.await(1, TimeUnit.SECONDS));
        bus.publish(event("2"));

        Thread stopper = new Thread(bus::stop);
        stopper.start();
        try {
            await(() -> !bus.status().accepting());
            assertFalse(bus.publish(event("3")).accepted());
            assertTrue(stopper.isAlive());
            handler.release.countDown();
            stopper.join(2_000L);

            assertFalse(stopper.isAlive());
            assertEquals(List.of("1", "2"), handler.values);
            assertFalse(bus.status().running());
        } finally {
            handler.release.countDown();
            stopper.join(2_000L);
            bus.stop();
        }
    }

    private static TradingProperties properties(TradingProperties.EventQueueFullPolicy policy) {
        TradingProperties properties = new TradingProperties();
        properties.getEventQueue().setCapacity(1);
        properties.getEventQueue().setFullPolicy(policy);
        properties.getEventQueue().setPollTimeoutMs(10L);
        return properties;
    }

    private static TradingEvent event(String value) {
        TickerResp ticker = new TickerResp();
        ticker.setLast(value);
        ticker.setTs("1710000000000");
        return TradingEvent.marketSnapshot(
                "BTC-USDT", TradingEventSource.OKX_WEBSOCKET, null, ticker, null
        );
    }

    private static String value(TradingEvent event) {
        return ((MarketSnapshotPayload) event.payload()).ticker().getLast();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static class BlockingHandler implements TradingEventHandler {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> values = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "blocking-test-handler";
        }

        @Override
        public boolean supports(TradingEvent event) {
            return true;
        }

        @Override
        public TradingEventHandlingResult handle(TradingEvent event) {
            firstStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            values.add(value(event));
            return TradingEventHandlingResult.PROCESSED;
        }
    }

    private static class FailsOnceHandler implements TradingEventHandler {
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> values = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "fails-once-test-handler";
        }

        @Override
        public boolean supports(TradingEvent event) {
            return true;
        }

        @Override
        public TradingEventHandlingResult handle(TradingEvent event) {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            values.add(value(event));
            return TradingEventHandlingResult.PROCESSED;
        }
    }
}
