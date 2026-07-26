package com.trade.trading.order;

import java.util.Optional;
import java.util.List;

public interface TradingOrderRepository {
    TradingOrder createOrGet(OrderSubmission submission);

    Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey);

    List<TradingOrder> findReconciliationCandidates(String instId, int limit);

    boolean compareAndSet(TradingOrder current, TradingOrder next, String reason);
}
