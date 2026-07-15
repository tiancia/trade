package com.trade.trading.persistence;

import com.trade.trading.order.OrderStatus;
import com.trade.trading.order.OrderSubmission;
import com.trade.trading.order.TradingOrder;
import com.trade.trading.order.TradingOrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
public class MyBatisTradingOrderRepository implements TradingOrderRepository {
    private final TradingOrderMapper mapper;

    public MyBatisTradingOrderRepository(TradingOrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TradingOrder createOrGet(OrderSubmission submission) {
        Instant now = Instant.now();
        TradingOrder candidate = new TradingOrder()
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
        mapper.insertIfAbsent(candidate);

        TradingOrder stored = mapper.findByIdempotencyKey(submission.idempotencyKey());
        if (stored == null) {
            throw new IllegalStateException("Order reservation disappeared: " + submission.idempotencyKey());
        }
        verifySameBusinessOrder(stored, submission);
        return stored;
    }

    @Override
    public Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.findByIdempotencyKey(idempotencyKey));
    }

    @Override
    @Transactional
    public boolean compareAndSet(TradingOrder current, TradingOrder next, String reason) {
        int updated = mapper.compareAndSet(current, next);
        if (updated != 1) {
            return false;
        }
        mapper.insertStatusHistory(
                current.getId(),
                current.getStatus().name(),
                next.getStatus().name(),
                next.getVersion(),
                reason
        );
        return true;
    }

    private static void verifySameBusinessOrder(TradingOrder stored, OrderSubmission requested) {
        if (!stored.getClientOrderId().equals(requested.clientOrderId())
                || !stored.getInstId().equals(requested.instId())
                || !stored.getAction().equals(requested.action())
                || stored.getRequestedSize().compareTo(requested.requestedSize()) != 0) {
            throw new IllegalStateException(
                    "Idempotency key was reused with different order parameters: " + requested.idempotencyKey()
            );
        }
    }
}
