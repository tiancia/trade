package com.trade.trading.application.runtime;

import com.trade.trading.application.port.TradingMarketFeed;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Controls trading producers; Spring manages the application-scoped event bus. */
@Component
public class TradingMarketDataRuntime implements SmartLifecycle {
    private final TradingMarketFeed webSocketFeed;
    private final TradingLeadershipService leadershipService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TradingMarketDataRuntime(
            TradingMarketFeed webSocketFeed,
            TradingLeadershipService leadershipService
    ) {
        this.webSocketFeed = webSocketFeed;
        this.leadershipService = leadershipService;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            leadershipService.start();
            webSocketFeed.start();
        } catch (RuntimeException e) {
            leadershipService.stop();
            running.set(false);
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        // The event bus is application-scoped because HTTP backtests and REST
        // collectors can publish while the scheduled trading task is stopped.
        webSocketFeed.stop();
        leadershipService.stop();
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
