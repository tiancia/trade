package com.trade.polymarket.infrastructure.persistence;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

import com.trade.polymarket.port.PolymarketExecutionGuard;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The current application configures one Polymarket account, guarded by one persistent primary row. */
@Component
public class MyBatisPolymarketExecutionGuard implements PolymarketExecutionGuard {
    private final Cache initializedRows = new TransactionAwareCacheDecorator(
            new ConcurrentMapCache("initialized-rows"));

    private final PolymarketExecutionGuardMapper mapper;

    public MyBatisPolymarketExecutionGuard(PolymarketExecutionGuardMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Long decisionId) {
        requireDecisionId(decisionId);
        initializeOnce("primary", () -> mapper.insertIfAbsent(), () -> mapper.lockGuard() != null);
        return mapper.reserve(decisionId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long decisionId) {
        requireDecisionId(decisionId);
        mapper.release(decisionId);
    }

    private static void requireDecisionId(Long decisionId) {
        if (decisionId == null || decisionId <= 0) {
            throw new IllegalArgumentException("A persisted positive Polymarket decision ID is required");
        }
    }
    /** Memoize only committed existence; values and transactional locks remain in MySQL. */
    private void initializeOnce(Object key, Runnable insert, java.util.function.BooleanSupplier exists) {
        if (initializedRows.get(key) != null) {
            if (exists.getAsBoolean()) {
                return;
            }
            // A restored or manually cleared database must not retain an old existence marker.
            initializedRows.evictIfPresent(key);
        }
        insert.run();
        // Eviction only repeats a safe insert; bound account and replay identities.
        if (((java.util.Map<?, ?>) initializedRows.getNativeCache()).size() >= 1024) {
            initializedRows.clear();
        }
        initializedRows.put(key, Boolean.TRUE);
    }
}
