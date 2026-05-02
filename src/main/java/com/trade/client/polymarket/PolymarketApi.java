package com.trade.client.polymarket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.dto.GammaMarket;
import com.trade.client.polymarket.dto.PolymarketLastTradePrice;
import com.trade.client.polymarket.dto.PolymarketOrderBook;

import java.util.List;
import java.util.Map;

public class PolymarketApi {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PolymarketHttpClient client;

    public PolymarketApi(PolymarketHttpClient client) {
        this.client = client;
    }

    public List<GammaMarket> listMarkets(Map<String, ?> queryParams) {
        return client.getGamma(
                PolymarketEndpoints.GAMMA_MARKETS,
                queryParams,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, GammaMarket.class)
        );
    }

    public PolymarketOrderBook getOrderBook(String tokenId) {
        return client.getClob(
                PolymarketEndpoints.CLOB_BOOK,
                Map.of("token_id", tokenId),
                OBJECT_MAPPER.getTypeFactory().constructType(PolymarketOrderBook.class)
        );
    }

    public PolymarketLastTradePrice getLastTradePrice(String tokenId) {
        return client.getClob(
                PolymarketEndpoints.CLOB_LAST_TRADE_PRICE,
                Map.of("token_id", tokenId),
                OBJECT_MAPPER.getTypeFactory().constructType(PolymarketLastTradePrice.class)
        );
    }

    public String getRawUrl(String url) {
        return client.getRawUrl(url);
    }
}
