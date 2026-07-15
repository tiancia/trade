package com.trade.trading.event;

import com.trade.trading.config.TradingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process bounded event bus for the trading module.
 *
 * <p>Producers only enqueue. One ordered consumer dispatches every event to all
 * matching handlers, isolating each handler failure so a bad database write or
 * consumer cannot terminate the pipeline.</p>
 */
@Component
public class BoundedTradingEventBus implements TradingEventPublisher, SmartLifecycle, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(BoundedTradingEventBus.class);

    private final TradingProperties.EventQueueProperties config;
    private final List<TradingEventHandler> handlers;
    private final MeterRegistry meterRegistry;
    private final BlockingDeque<TradingEvent> queue;
    private final Object overflowLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong consumedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public BoundedTradingEventBus(
            TradingProperties properties,
            List<TradingEventHandler> handlers,
            MeterRegistry meterRegistry
    ) {
        this.config = Objects.requireNonNull(properties.getEventQueue(), "eventQueue config must not be null");
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
        this.meterRegistry = meterRegistry;
        this.queue = new LinkedBlockingDeque<>(Math.max(config.getCapacity(), 1));

        Gauge.builder("trade.trading.events.queue.depth", queue, BlockingDeque::size)
                .description("Trading events waiting for the consumer")
                .register(meterRegistry);
        Gauge.builder("trade.trading.events.queue.remaining", queue, BlockingDeque::remainingCapacity)
                .description("Remaining trading event queue capacity")
                .register(meterRegistry);
        Gauge.builder("trade.trading.events.queue.capacity", queue,
                        value -> value.size() + value.remainingCapacity())
                .description("Configured trading event queue capacity")
                .register(meterRegistry);
    }

    public synchronized void start() {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive()) {
            if (!accepting.get()) {
                log.warn("Cannot restart trading event bus while the previous consumer is still stopping");
            }
            return;
        }

        queue.clear();
        accepting.set(true);
        Thread next = new Thread(this::consume, "trading-event-consumer");
        next.setDaemon(true);
        worker.set(next);
        next.start();
        log.info("Trading event bus started: capacity={}, backpressure={}, handlers={}",
                capacity(), config.getFullPolicy(), handlers.size());
    }

    @Override
    public boolean isRunning() {
        Thread currentWorker = worker.get();
        return currentWorker != null && currentWorker.isAlive();
    }

    @Override
    public int getPhase() {
        // Start before ordinary producers and stop after them.
        return Integer.MIN_VALUE;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    public synchronized void stop() {
        accepting.set(false);
        Thread currentWorker = worker.get();
        if (currentWorker == null || currentWorker == Thread.currentThread()) {
            return;
        }

        long timeoutMs = Math.max(config.getShutdownTimeoutMs(), 0L);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean interrupted = false;
        while (currentWorker.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(currentWorker, remainingNanos);
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        if (currentWorker.isAlive()) {
            int abandoned;
            synchronized (overflowLock) {
                abandoned = queue.size();
                TradingEvent event;
                while ((event = queue.pollFirst()) != null) {
                    recordDropped(event, "shutdown_timeout");
                }
            }
            currentWorker.interrupt();
            log.warn("Timed out draining trading event queue: timeoutMs={}, abandoned={}", timeoutMs, abandoned);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public TradingEventPublishResult publish(TradingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!accepting.get()) {
            recordPublished(event, TradingEventPublishResult.REJECTED_NOT_RUNNING);
            return TradingEventPublishResult.REJECTED_NOT_RUNNING;
        }

        return switch (config.getFullPolicy()) {
            case DROP_OLDEST -> publishDroppingOldest(event);
            case DROP_LATEST -> publishDroppingLatest(event);
            case BLOCK -> publishBlocking(event);
        };
    }

    public TradingEventBusStatus status() {
        Thread currentWorker = worker.get();
        return new TradingEventBusStatus(
                currentWorker != null && currentWorker.isAlive(),
                accepting.get(),
                queue.size(),
                capacity(),
                handlers.size(),
                acceptedCount.get(),
                droppedCount.get(),
                consumedCount.get(),
                failedCount.get()
        );
    }

    private TradingEventPublishResult publishDroppingLatest(TradingEvent event) {
        if (queue.offerLast(event)) {
            return finishAccepted(event, TradingEventPublishResult.ACCEPTED);
        }
        recordDropped(event, "queue_full_drop_latest");
        recordPublished(event, TradingEventPublishResult.DROPPED_LATEST);
        return TradingEventPublishResult.DROPPED_LATEST;
    }

    private TradingEventPublishResult publishDroppingOldest(TradingEvent event) {
        if (queue.offerLast(event)) {
            return finishAccepted(event, TradingEventPublishResult.ACCEPTED);
        }

        synchronized (overflowLock) {
            if (queue.offerLast(event)) {
                return finishAccepted(event, TradingEventPublishResult.ACCEPTED);
            }
            TradingEvent discarded = queue.pollFirst();
            if (discarded != null) {
                recordDropped(discarded, "queue_full_drop_oldest");
            }
            if (queue.offerLast(event)) {
                return finishAccepted(event, discarded == null
                        ? TradingEventPublishResult.ACCEPTED
                        : TradingEventPublishResult.ACCEPTED_AFTER_DROPPING_OLDEST);
            }
        }

        recordDropped(event, "queue_full_race");
        recordPublished(event, TradingEventPublishResult.DROPPED_LATEST);
        return TradingEventPublishResult.DROPPED_LATEST;
    }

    private TradingEventPublishResult publishBlocking(TradingEvent event) {
        try {
            boolean offered = queue.offerLast(
                    event,
                    Math.max(config.getPublishTimeoutMs(), 0L),
                    TimeUnit.MILLISECONDS
            );
            if (offered) {
                return finishAccepted(event, TradingEventPublishResult.ACCEPTED);
            }
            recordDropped(event, "publish_timeout");
            recordPublished(event, TradingEventPublishResult.TIMED_OUT);
            return TradingEventPublishResult.TIMED_OUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordDropped(event, "publisher_interrupted");
            recordPublished(event, TradingEventPublishResult.INTERRUPTED);
            return TradingEventPublishResult.INTERRUPTED;
        }
    }

    private TradingEventPublishResult finishAccepted(
            TradingEvent event,
            TradingEventPublishResult acceptedResult
    ) {
        // Close the race where shutdown starts after the first accepting check.
        // If the consumer already took it, it belongs to the normal drain.
        if (!accepting.get() && queue.remove(event)) {
            recordDropped(event, "bus_stopping");
            recordPublished(event, TradingEventPublishResult.REJECTED_NOT_RUNNING);
            return TradingEventPublishResult.REJECTED_NOT_RUNNING;
        }
        acceptedCount.incrementAndGet();
        recordPublished(event, acceptedResult);
        return acceptedResult;
    }

    private void consume() {
        Thread current = Thread.currentThread();
        try {
            while (accepting.get() || !queue.isEmpty()) {
                try {
                    TradingEvent event = queue.pollFirst(
                            Math.max(config.getPollTimeoutMs(), 1L),
                            TimeUnit.MILLISECONDS
                    );
                    if (event != null) {
                        dispatch(event);
                    }
                } catch (InterruptedException e) {
                    if (accepting.get() || !queue.isEmpty()) {
                        log.debug("Trading event consumer interrupted while events remain");
                    }
                } catch (RuntimeException e) {
                    // A defensive boundary around dispatch itself. Individual
                    // handler exceptions are isolated more precisely below.
                    failedCount.incrementAndGet();
                    log.error("Unexpected trading event dispatch failure", e);
                }
            }
        } finally {
            accepting.set(false);
            worker.compareAndSet(current, null);
            log.info("Trading event bus stopped: remainingQueueDepth={}", queue.size());
        }
    }

    private void dispatch(TradingEvent event) {
        recordQueueLatency(event);
        int matchingHandlers = 0;
        boolean failed = false;

        for (TradingEventHandler handler : handlers) {
            boolean supported;
            try {
                supported = handler.supports(event);
            } catch (Exception e) {
                failed = true;
                failedCount.incrementAndGet();
                recordHandled(event, handler, "supports_failed", 0L);
                log.warn("Trading event handler supports check failed: eventId={}, handler={}",
                        event.eventId(), handler.name(), e);
                continue;
            }
            if (!supported) {
                continue;
            }

            matchingHandlers++;
            long startedAt = System.nanoTime();
            try {
                TradingEventHandlingResult result = handler.handle(event);
                TradingEventHandlingResult safeResult = result == null
                        ? TradingEventHandlingResult.FAILED
                        : result;
                recordHandled(event, handler, metricValue(safeResult), System.nanoTime() - startedAt);
                if (safeResult == TradingEventHandlingResult.FAILED) {
                    failed = true;
                    failedCount.incrementAndGet();
                }
            } catch (Exception e) {
                failed = true;
                failedCount.incrementAndGet();
                recordHandled(event, handler, "failed", System.nanoTime() - startedAt);
                log.warn("Trading event handler failed: eventId={}, type={}, handler={}",
                        event.eventId(), event.type(), handler.name(), e);
            }
        }

        consumedCount.incrementAndGet();
        String outcome = failed ? "partial_failure" : matchingHandlers == 0 ? "unhandled" : "processed";
        meterRegistry.counter(
                "trade.trading.events.consumed",
                "type", metricValue(event.type()),
                "outcome", outcome
        ).increment();
    }

    private void recordPublished(TradingEvent event, TradingEventPublishResult result) {
        meterRegistry.counter(
                "trade.trading.events.published",
                "type", metricValue(event.type()),
                "source", metricValue(event.source()),
                "outcome", metricValue(result)
        ).increment();
    }

    private void recordDropped(TradingEvent event, String reason) {
        long total = droppedCount.incrementAndGet();
        meterRegistry.counter(
                "trade.trading.events.dropped",
                "type", metricValue(event.type()),
                "source", metricValue(event.source()),
                "reason", reason
        ).increment();
        if (total == 1 || total % 100 == 0) {
            log.warn("Trading events dropped: reason={}, totalDropped={}, queueDepth={}",
                    reason, total, queue.size());
        }
    }

    private void recordQueueLatency(TradingEvent event) {
        long nanos = Math.max(Duration.between(event.receivedAt(), Instant.now()).toNanos(), 0L);
        Timer.builder("trade.trading.events.queue.latency")
                .description("Time between event receipt and consumer dispatch")
                .tag("type", metricValue(event.type()))
                .register(meterRegistry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    private void recordHandled(
            TradingEvent event,
            TradingEventHandler handler,
            String outcome,
            long durationNanos
    ) {
        meterRegistry.counter(
                "trade.trading.events.handled",
                "handler", handler.name(),
                "type", metricValue(event.type()),
                "outcome", outcome
        ).increment();
        Timer.builder("trade.trading.events.handler.duration")
                .description("Trading event handler duration")
                .tag("handler", handler.name())
                .tag("type", metricValue(event.type()))
                .register(meterRegistry)
                .record(Math.max(durationNanos, 0L), TimeUnit.NANOSECONDS);
    }

    private int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    private static String metricValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
