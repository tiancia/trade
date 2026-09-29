package com.trade.trading.infrastructure.persistence;

import com.trade.trading.application.port.TradingOrderRepository;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.TradingOrder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
        TradingOrderRow candidate = TradingOrderRow.fromDomain(TradingOrder.pending(submission, now));
        mapper.insertIfAbsent(candidate);

        TradingOrderRow storedRow = mapper.findByIdempotencyKey(submission.idempotencyKey());
        if (storedRow == null) {
            throw new IllegalStateException("Order reservation disappeared: " + submission.idempotencyKey());
        }
        TradingOrder stored = storedRow.toDomain();
        verifySameBusinessOrder(stored, submission);
        return stored;
    }

    @Override
    public Optional<TradingOrder> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.findByIdempotencyKey(idempotencyKey)).map(TradingOrderRow::toDomain);
    }

    @Override
    public List<TradingOrder> findReconciliationCandidates(String instId, int limit) {
        return mapper.findReconciliationCandidates(instId, Math.max(1, limit)).stream().map(TradingOrderRow::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean compareAndSet(TradingOrder current, TradingOrder next, String reason) {
        int updated = mapper.compareAndSet(TradingOrderRow.fromDomain(current), TradingOrderRow.fromDomain(next));
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
