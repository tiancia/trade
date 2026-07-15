package com.trade.trading.application;

import com.trade.trading.event.BoundedTradingEventBus;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates producer and consumer order for start and graceful shutdown. */
@Component
public class TradingMarketDataRuntime implements SmartLifecycle {
    private final BoundedTradingEventBus eventBus;
    private final OkxMarketDataWebSocketFeed webSocketFeed;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TradingMarketDataRuntime(
            BoundedTradingEventBus eventBus,
            OkxMarketDataWebSocketFeed webSocketFeed
    ) {
        this.eventBus = eventBus;
        this.webSocketFeed = webSocketFeed;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        eventBus.start();
        try {
            webSocketFeed.start();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        // The event bus is application-scoped because HTTP backtests and REST
        // collectors can publish while the scheduled trading task is stopped.
        webSocketFeed.stop();
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        // AutomationTaskManager owns the trading task's explicit startup gate.
        return false;
    }

    @Override
    public int getPhase() {
        // Stop before the event bus (Integer.MIN_VALUE), so accepted events drain.
        return 0;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }
}
