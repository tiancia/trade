package com.trade.trading.event;

/** Origin of a trading event. */
public enum TradingEventSource {
    OKX_WEBSOCKET("WEBSOCKET_TICKER"),
    OKX_REST_DECISION("REST_DECISION"),
    OKX_REST_EVENT_FALLBACK("REST_EVENT_FALLBACK"),
    OKX_REST_HISTORY("REST_HISTORY");

    private final String persistenceValue;

    TradingEventSource(String persistenceValue) {
        this.persistenceValue = persistenceValue;
    }

    public String persistenceValue() {
        return persistenceValue;
    }
}
