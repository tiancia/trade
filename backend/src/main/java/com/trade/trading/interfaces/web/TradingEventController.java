package com.trade.trading.interfaces.web;

import com.trade.trading.application.event.TradingEventBusStatus;
import com.trade.trading.application.runtime.TradingEventStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational view of the in-process trading event pipeline. */
@RestController
@RequestMapping("/api/trading/runtime/events")
public class TradingEventController {
    private final TradingEventStatusService eventBus;

    public TradingEventController(TradingEventStatusService eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping
    public TradingEventBusStatus status() {
        return eventBus.status();
    }
}
