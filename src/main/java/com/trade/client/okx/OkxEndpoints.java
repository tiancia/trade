package com.trade.client.okx;

public final class OkxEndpoints {
    public static final String DEFAULT_BASE_URL = "https://www.okx.com";
    public static final String DEFAULT_WS_PUBLIC_URL = "wss://ws.okx.com:8443/ws/v5/public";
    public static final String DEFAULT_WS_PRIVATE_URL = "wss://ws.okx.com:8443/ws/v5/private";

    public static final String INSTRUMENT_INFO = "/api/v5/account/instruments";
    public static final String SYSTEM_TIME = "/api/v5/public/time";
    public static final String ACCOUNT_BALANCE = "/api/v5/account/balance";
    public static final String MARKET_TICKER = "/api/v5/market/ticker";
    public static final String MARKET_BOOKS = "/api/v5/market/books";
    public static final String MARKET_CANDLES = "/api/v5/market/candles";
    public static final String MARKET_HISTORY_CANDLES = "/api/v5/market/history-candles";
    public static final String TRADE_ORDER = "/api/v5/trade/order";
    public static final String TRADE_CANCEL_ORDER = "/api/v5/trade/cancel-order";
    public static final String TRADE_PENDING_ORDERS = "/api/v5/trade/orders-pending";
    public static final String TRADE_ORDER_HISTORY = "/api/v5/trade/orders-history";
    public static final String TRADE_FILLS = "/api/v5/trade/fills";
    public static final String TRADE_CANCEL_ALL_AFTER = "/api/v5/trade/cancel-all-after";

    private OkxEndpoints() {
    }
}
