package com.trade.trading.web;

import com.trade.trading.event.BoundedTradingEventBus;
import com.trade.trading.event.TradingEventBusStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational view of the in-process trading event pipeline. */
@RestController
@RequestMapping("/api/trading/runtime/events")
public class TradingEventController {
    private final BoundedTradingEventBus eventBus;

    public TradingEventController(BoundedTradingEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping
    public TradingEventBusStatus status() {
        return eventBus.status();
    }
}
