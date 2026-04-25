package com.trade.trading;

import java.util.Map;

public record TradingEvent(String type, String reason, Map<String, Object> details) {
}
