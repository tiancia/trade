package com.trade.trading.application.port;

import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.TradingOrder;

import java.util.List;
import java.util.Optional;

public interface TradingOrderRepository {
    TradingOrder createOrGet(OrderSubmission submission);

    Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey);

    List<TradingOrder> findReconciliationCandidates(String instId, int limit);

    boolean compareAndSet(TradingOrder current, TradingOrder next, String reason);
}
