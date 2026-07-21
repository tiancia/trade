package com.trade.trading.web;

import com.trade.trading.event.CandleBatchPayload;
import com.trade.trading.event.TradingEvent;
import com.trade.trading.event.TradingEventHandler;
import com.trade.trading.event.TradingEventHandlingResult;
import com.trade.trading.event.TradingEventType;
import com.trade.trading.market.CandleSeries;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fan-out bridge from the bounded trading event bus to browser K-line streams. */
@Component
public class TradingCandleStream implements TradingEventHandler, DisposableBean {
    private static final int SEND_QUEUE_CAPACITY = 512;

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final Counter droppedCounter;
    private final Counter failedCounter;
    private final ThreadPoolExecutor senderPool;

    public TradingCandleStream(MeterRegistry meterRegistry) {
        this.droppedCounter = Counter.builder("trade.trading.candles.stream.dropped")
                .description("Browser candle updates dropped by stream backpressure")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("trade.trading.candles.stream.failed")
                .description("Browser candle stream send failures")
                .register(meterRegistry);
        this.senderPool = new ThreadPoolExecutor(
                1,
                4,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "trading-candle-sse");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> droppedCounter.increment()
        );
        Gauge.builder("trade.trading.candles.stream.clients", clients, CopyOnWriteArrayList::size)
                .description("Connected browser K-line streams")
                .register(meterRegistry);
        Gauge.builder("trade.trading.candles.stream.queue.depth", senderPool, executor -> executor.getQueue().size())
                .description("K-line stream sends waiting for an I/O worker")
                .register(meterRegistry);
    }

    public SseEmitter subscribe(CandleSeries snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("candle snapshot is required");
        }
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(UUID.randomUUID().toString(), snapshot.instrumentId(), snapshot.bar(), emitter);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> remove(client));
        emitter.onError(error -> clients.remove(client));
        try {
            emitter.send(SseEmitter.event()
                    .id("snapshot-" + client.id())
                    .name("snapshot")
                    .data(snapshot));
        } catch (IOException e) {
            remove(client);
            throw new IllegalStateException("Open candle stream failed", e);
        }
        return emitter;
    }

    @Override
    public String name() {
        return "trading-candle-sse";
    }

    @Override
    public boolean supports(TradingEvent event) {
        return event != null
                && event.type() == TradingEventType.CANDLE_BATCH
                && event.payload() instanceof CandleBatchPayload;
    }

    @Override
    public TradingEventHandlingResult handle(TradingEvent event) {
        if (!supports(event)) {
            return TradingEventHandlingResult.SKIPPED;
        }
        CandleBatchPayload payload = (CandleBatchPayload) event.payload();
        CandleStreamUpdate update = new CandleStreamUpdate(
                event.eventId(),
                event.instrumentId(),
                payload.bar(),
                event.receivedAt(),
                payload.candles()
        );
        boolean submitted = false;
        for (Client client : clients) {
            if (!client.instrumentId().equalsIgnoreCase(event.instrumentId())
                    || !normalize(client.bar()).equals(normalize(payload.bar()))) {
                continue;
            }
            submitted = true;
            senderPool.execute(() -> send(client, update));
        }
        return submitted ? TradingEventHandlingResult.PROCESSED : TradingEventHandlingResult.SKIPPED;
    }

    @Override
    public void destroy() {
        for (Client client : clients) {
            remove(client);
        }
        senderPool.shutdownNow();
    }

    private void send(Client client, CandleStreamUpdate update) {
        try {
            client.emitter().send(SseEmitter.event()
                    .id(update.eventId())
                    .name("candle")
                    .data(update));
        } catch (Exception e) {
            failedCounter.increment();
            remove(client);
        }
    }

    private void remove(Client client) {
        clients.remove(client);
        try {
            client.emitter().complete();
        } catch (RuntimeException ignored) {
            // Completion is best effort after disconnects.
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Client(String id, String instrumentId, String bar, SseEmitter emitter) {
    }
}
