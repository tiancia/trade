package com.trade.trading.order;

import java.time.Instant;
import java.util.LinkedHashMap;
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
        TradingOrder created = new TradingOrder()
                .setId(ids.incrementAndGet())
                .setIdempotencyKey(submission.idempotencyKey())
                .setClientOrderId(submission.clientOrderId())
                .setDecisionId(submission.decisionId())
                .setStrategyId(submission.strategyId())
                .setInstId(submission.instId())
                .setAction(submission.action())
                .setSide(submission.side())
                .setTdMode(submission.tdMode())
                .setOrderType(submission.orderType())
                .setTargetCurrency(submission.targetCurrency())
                .setRequestedSize(submission.requestedSize())
                .setStatus(OrderStatus.PENDING_SUBMIT)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        orders.put(submission.idempotencyKey(), created);
        return copy(created);
    }

    @Override
    public synchronized Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(orders.get(idempotencyKey)).map(InMemoryTradingOrderRepository::copy);
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
        return new TradingOrder()
                .setId(source.getId())
                .setIdempotencyKey(source.getIdempotencyKey())
                .setClientOrderId(source.getClientOrderId())
                .setExchangeOrderId(source.getExchangeOrderId())
                .setDecisionId(source.getDecisionId())
                .setStrategyId(source.getStrategyId())
                .setInstId(source.getInstId())
                .setAction(source.getAction())
                .setSide(source.getSide())
                .setTdMode(source.getTdMode())
                .setOrderType(source.getOrderType())
                .setTargetCurrency(source.getTargetCurrency())
                .setRequestedSize(source.getRequestedSize())
                .setStatus(source.getStatus())
                .setVersion(source.getVersion())
                .setFilledBaseAmount(source.getFilledBaseAmount())
                .setAverageFillPrice(source.getAverageFillPrice())
                .setFee(source.getFee())
                .setFeeCcy(source.getFeeCcy())
                .setFailureCode(source.getFailureCode())
                .setFailureMessage(source.getFailureMessage())
                .setCreatedAt(source.getCreatedAt())
                .setUpdatedAt(source.getUpdatedAt())
                .setSubmittedAt(source.getSubmittedAt())
                .setCompletedAt(source.getCompletedAt());
    }
}
