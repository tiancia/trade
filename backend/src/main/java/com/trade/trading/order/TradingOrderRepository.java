package com.trade.trading.order;

import java.util.Optional;

public interface TradingOrderRepository {
    TradingOrder createOrGet(OrderSubmission submission);

    Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey);

    boolean compareAndSet(TradingOrder current, TradingOrder next, String reason);
}
