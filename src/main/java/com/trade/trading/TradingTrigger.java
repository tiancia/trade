package com.trade.trading;

import java.util.Map;

public record TradingTrigger(String type, String reason, Map<String, Object> details) {
    public static TradingTrigger scheduled() {
        return new TradingTrigger("SCHEDULED", "30 minute scheduled decision", Map.of());
    }

    public static TradingTrigger event(String reason, Map<String, Object> details) {
        return new TradingTrigger("EVENT", reason, details == null ? Map.of() : details);
    }
}
