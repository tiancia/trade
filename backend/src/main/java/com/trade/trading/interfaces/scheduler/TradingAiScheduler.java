package com.trade.trading.interfaces.scheduler;

import com.trade.trading.application.strategy.TradingAiAdvisor;
import org.springframework.stereotype.Component;

@Component
public class TradingAiScheduler {
    private final TradingAiAdvisor advisor;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    public TradingAiScheduler(TradingAiAdvisor advisor) { this.advisor = advisor; }
    public void start() { generation.incrementAndGet(); running = true; }
    public void stop() { running = false; generation.incrementAndGet(); }
    public void review() {
        long current = generation.get();
        advisor.review(() -> running && generation.get() == current);
    }
}
