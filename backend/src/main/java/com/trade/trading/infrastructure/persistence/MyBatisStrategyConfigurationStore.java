package com.trade.trading.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.trade.trading.application.port.StrategyConfigurationStore;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

@Repository
public class MyBatisStrategyConfigurationStore implements StrategyConfigurationStore {
    private final StrategyConfigurationMapper mapper;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public MyBatisStrategyConfigurationStore(StrategyConfigurationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public StrategyConfigurationVersion currentOrCreate(StrategyConfigurationVersion baseline) {
        StrategyConfigurationRow existing = mapper.findCurrent(baseline.strategyId());
        if (existing != null) { return snapshot(existing); }
        StrategyConfigurationRow row = row(baseline);
        if (mapper.insertHeadIfAbsent(row) == 1) {
            mapper.insertHistory(row);
        }
        // A locking read observes the winning initialization even under MySQL
        // REPEATABLE READ when our earlier nonlocking read saw no row.
        return snapshot(required(mapper.lockCurrent(baseline.strategyId())));
    }

    @Override
    @Transactional
    public StrategyConfigurationVersion update(long expectedVersion, StrategyConfigurationVersion next) {
        StrategyConfigurationRow current = required(mapper.lockCurrent(next.strategyId()));
        if (current.getConfigVersion() != expectedVersion) {
            throw new ConcurrentModificationException("Strategy configuration changed from version "
                    + expectedVersion + " to " + current.getConfigVersion());
        }
        if (next.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Next strategy configuration version must increase by one");
        }
        StrategyConfigurationRow row = row(next);
        mapper.insertHistory(row);
        if (mapper.updateHead(row) != 1) {
            throw new IllegalStateException("Strategy configuration update was not persisted");
        }
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyConfigurationVersion> history(String strategyId, int offset, int limit) {
        return mapper.findHistory(strategyId, offset, limit).stream().map(this::snapshot).toList();
    }

    private StrategyConfigurationRow row(StrategyConfigurationVersion snapshot) {
        try {
            StrategyConfigurationRow row = new StrategyConfigurationRow();
            row.setStrategyId(snapshot.strategyId());
            row.setStrategyType(snapshot.type());
            row.setBar(snapshot.bar());
            row.setConfigVersion(snapshot.version());
            row.setParamsJson(json.writeValueAsString(snapshot.params()));
            row.setReason(snapshot.reason());
            row.setChangedAt(Instant.parse(snapshot.changedAt()));
            return row;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid strategy configuration snapshot", e);
        }
    }

    private StrategyConfigurationVersion snapshot(StrategyConfigurationRow row) {
        try {
            Map<String, Object> params = json.readValue(row.getParamsJson(), new TypeReference<>() {});
            return new StrategyConfigurationVersion(row.getStrategyId(), row.getStrategyType(), row.getBar(),
                    row.getConfigVersion(), row.getChangedAt().toString(), row.getReason(), params);
        } catch (Exception e) {
            throw new IllegalStateException("Stored strategy configuration cannot be read", e);
        }
    }

    private static StrategyConfigurationRow required(StrategyConfigurationRow row) {
        if (row == null) {
            throw new IllegalStateException("Strategy configuration is not initialized");
        }
        return row;
    }
}
