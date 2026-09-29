package com.trade.trading.application.runtime;

import com.trade.trading.application.event.TradingEventBusStatus;
import com.trade.trading.application.port.TradingEventPipeline;
import org.springframework.stereotype.Service;

/** Operational query independent of the queue implementation. */
@Service
public class TradingEventStatusService {
    private final TradingEventPipeline pipeline;

    public TradingEventStatusService(TradingEventPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public TradingEventBusStatus status() {
        return pipeline.status();
    }
}
