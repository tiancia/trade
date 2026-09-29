package com.trade.trading.application.order;

import com.trade.trading.application.port.TradingOrderRepository;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.TradingOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Test double that preserves the repository's unique-key and CAS semantics. */
public class InMemoryTradingOrderRepository implements TradingOrderRepository {
    private final Map<String, TradingOrder> orders = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private int transitionCount;

    @Override
    public synchronized TradingOrder createOrGet(OrderSubmission submission) {
        TradingOrder existing = orders.get(submission.idempotencyKey());
        if (existing != null) {
            return copy(existing);
        }
        Instant now = Instant.now();
        TradingOrder created = TradingOrder.restore(TradingOrder.pending(submission, now).snapshot()
                .toBuilder().id(ids.incrementAndGet()).build());
        orders.put(submission.idempotencyKey(), created);
        return copy(created);
    }

    @Override
    public synchronized Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(orders.get(idempotencyKey)).map(InMemoryTradingOrderRepository::copy);
    }

    @Override
    public synchronized List<TradingOrder> findReconciliationCandidates(String instId, int limit) {
        return orders.values().stream()
                .filter(order -> order.getInstId().equals(instId))
                .filter(order -> !order.getStatus().isTerminal())
                .limit(Math.max(1, limit))
                .map(InMemoryTradingOrderRepository::copy)
                .toList();
    }

    @Override
    public synchronized boolean compareAndSet(TradingOrder current, TradingOrder next, String reason) {
        TradingOrder stored = orders.get(current.getIdempotencyKey());
        if (stored == null
                || stored.getStatus() != current.getStatus()
                || stored.getVersion() != current.getVersion()) {
            return false;
        }
        orders.put(current.getIdempotencyKey(), copy(next));
        transitionCount++;
        return true;
    }

    public synchronized int transitionCount() {
        return transitionCount;
    }

    private static TradingOrder copy(TradingOrder source) {
        return source; // Orders are immutable.
    }
}
