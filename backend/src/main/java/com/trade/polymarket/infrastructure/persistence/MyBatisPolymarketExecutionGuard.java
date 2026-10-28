package com.trade.polymarket.infrastructure.persistence;

import com.trade.polymarket.port.PolymarketExecutionGuard;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The current application configures one Polymarket account, guarded by one persistent primary row. */
@Component
public class MyBatisPolymarketExecutionGuard implements PolymarketExecutionGuard {
    private final PolymarketExecutionGuardMapper mapper;

    public MyBatisPolymarketExecutionGuard(PolymarketExecutionGuardMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Long decisionId) {
        requireDecisionId(decisionId);
        mapper.insertIfAbsent();
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
}
