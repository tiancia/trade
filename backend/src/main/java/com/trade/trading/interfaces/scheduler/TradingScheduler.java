package com.trade.trading.interfaces.scheduler;

import com.trade.trading.application.runtime.TradingTriggerService;
import org.springframework.stereotype.Component;

/** Inbound automation trigger; all use-case orchestration belongs to application. */
@Component
public class TradingScheduler {
    private final TradingTriggerService triggers;

    public TradingScheduler(TradingTriggerService triggers) {
        this.triggers = triggers;
    }

    public void runScheduledDecision() {
        triggers.runScheduledDecision();
    }

    public void reconcileOrders() {
        triggers.reconcileOrders();
    }

    public void scanEventTriggers() {
        triggers.scanEventTriggers();
    }
}
