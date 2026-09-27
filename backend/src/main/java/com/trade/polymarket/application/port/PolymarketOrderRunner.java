package com.trade.polymarket.application.port;

import com.trade.polymarket.domain.model.PolymarketOrderRequest;

public interface PolymarketOrderRunner {
    String placeOrder(PolymarketOrderRequest request);
}
