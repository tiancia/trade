package com.trade.trading.infrastructure.persistence;

import com.trade.trading.domain.model.ActiveStrategySelection;
import com.trade.trading.port.StrategySelectionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.function.Supplier;

@Repository
public class MyBatisStrategySelectionStore implements StrategySelectionStore {
    private final StrategySelectionMapper mapper;
    public MyBatisStrategySelectionStore(StrategySelectionMapper mapper) { this.mapper = mapper; }

    @Override @Transactional
    public ActiveStrategySelection currentOrCreate(String scope, String instId, ActiveStrategySelection seed) {
        StrategySelectionRow row = mapper.find(scope, instId);
        if (row == null) {
            mapper.insertIfAbsent(new StrategySelectionRow().setAccountScope(scope).setInstId(instId)
                    .setStrategyId(seed.strategyId()).setRevision(seed.revision()).setChangedAt(seed.changedAt()));
            row = mapper.lock(scope, instId);
        }
        return snapshot(row);
    }

    @Override @Transactional
    public ActiveStrategySelection select(String scope, String instId, String id, Long expectedRevision) {
        return withLock(() -> {
            currentOrCreate(scope, instId, new ActiveStrategySelection(null, 0, null));
            StrategySelectionRow row = mapper.lock(scope, instId);
            if (expectedRevision != null && row.getRevision() != expectedRevision) {
                throw new ConcurrentModificationException("Active strategy changed; refresh and retry");
            }
            if (!Objects.equals(id, row.getStrategyId())) {
                if (mapper.hasExposure()) throw new ConcurrentModificationException("Close managed positions and reconcile all orders before changing strategy");
                row.setStrategyId(id).setRevision(row.getRevision() + 1)
                        .setChangedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                if (mapper.update(row) != 1) throw new ConcurrentModificationException("Active strategy changed; refresh and retry");
            }
            return snapshot(row);
        });
    }

    @Override @Transactional
    public <T> T withLock(Supplier<T> action) {
        // Shared configuration IDs span execution modes. One short global fence
        // has a single lock order: fence -> configuration -> account/order rows.
        mapper.insertIfAbsent(new StrategySelectionRow().setAccountScope("__guard__").setInstId("__guard__"));
        mapper.lock("__guard__", "__guard__");
        return action.get();
    }
    @Override public boolean hasExposure() { return mapper.hasExposure(); }
    private static ActiveStrategySelection snapshot(StrategySelectionRow row) {
        if (row == null) throw new IllegalStateException("Shared strategy selection is unavailable");
        return new ActiveStrategySelection(row.getStrategyId(), row.getRevision(), row.getChangedAt());
    }
}
