package com.trade.trading.model;

import java.util.Map;

/** A derived market condition that can trigger a strategy evaluation. */
public record MarketSignal(String type, String reason, Map<String, Object> details) {
    public MarketSignal {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
