package com.trade.polymarket.execution;

import com.trade.polymarket.model.PolymarketOrderRequest;

public interface PolymarketOrderRunner {
    String placeOrder(PolymarketOrderRequest request);
}
