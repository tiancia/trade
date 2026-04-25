package com.trade.constdef;

public class RequestPath {
    public static final String DOMAIN = "https://www.okx.com";

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

    public static final String WS_PUBLIC_URL = "wss://ws.okx.com:8443/ws/v5/public";
    public static final String WS_PRIVATE_URL = "wss://ws.okx.com:8443/ws/v5/private";

    public static String getOkSecretKey() {
        return System.getenv("OKX-SECRET-KEY");
    }

    public static String getOkAccessKey() {
        return System.getenv("OKX-ACCESS-KEY");
    }

    public static String getOkAccessPassphrase() {
        return System.getenv("OKX-ACCESS-PASSPHRASE");
    }

    public static String getInstrumentInfoPath() {
        return DOMAIN + INSTRUMENT_INFO;
    }

    private static String requireEnv(String primaryName, String fallbackName) {
        String value = System.getenv(primaryName);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallbackName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + primaryName + " or " + fallbackName
            );
        }
        return value;
    }
}
