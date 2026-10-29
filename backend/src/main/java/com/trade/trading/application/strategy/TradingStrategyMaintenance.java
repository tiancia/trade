package com.trade.trading.application.strategy;

import com.trade.trading.domain.model.ActiveStrategySelection;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.StrategyConfigurationStore;
import com.trade.trading.port.StrategySelectionStore;
import org.springframework.stereotype.Service;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Fences maintenance against PAPER fills and LIVE reservations without holding a transaction during exchange I/O. */
@Service
public class TradingStrategyMaintenance {
    private final StrategySelectionStore selections;
    private final StrategyConfigurationStore configurations;
    private final TradingProperties properties;
    public TradingStrategyMaintenance(StrategySelectionStore selections, StrategyConfigurationStore configurations,
                                      TradingProperties properties) {
        this.selections = selections; this.configurations = configurations; this.properties = properties;
    }
    public <T> T change(Supplier<T> action) {
        return selections.withLock(() -> {
            if (selections.hasExposure()) throw new ConcurrentModificationException(
                    "Close managed positions and reconcile all orders before changing strategy parameters or selection");
            return action.get();
        });
    }
    public <T> T execute(TradingDecisionRecord record, Supplier<T> action) {
        return selections.withLock(() -> {
            ActiveStrategySelection current = selections.currentOrCreate(properties.financialAccountScope(), properties.getInstId(),
                    new ActiveStrategySelection(null, 0, null));
            if (!Objects.equals(current.strategyId(), record.getStrategyId())
                    || current.revision() != record.getActiveStrategyRevision()) {
                throw new ConcurrentModificationException("Active strategy changed while collecting this decision; wait for a fresh decision");
            }
            return configurations.withUnchangedVersions(Map.of(record.getStrategyId(), record.getStrategyConfigVersion()), action);
        });
    }
}
