package com.trade.polymarket.port;

import com.trade.polymarket.domain.model.PolymarketOrderRequest;

public interface PolymarketOrderRunner {
    String placeOrder(PolymarketOrderRequest request);
}
