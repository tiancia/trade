package com.trade.client.polymarket;

public final class PolymarketEndpoints {
    public static final String DEFAULT_GAMMA_BASE_URL = "https://gamma-api.polymarket.com";
    public static final String DEFAULT_CLOB_BASE_URL = "https://clob.polymarket.com";

    public static final String GAMMA_MARKETS = "/markets";
    public static final String CLOB_BOOK = "/book";
    public static final String CLOB_LAST_TRADE_PRICE = "/last-trade-price";

    private PolymarketEndpoints() {
    }
}
