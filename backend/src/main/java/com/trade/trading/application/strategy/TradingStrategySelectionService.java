package com.trade.trading.application.strategy;

import com.trade.trading.domain.model.ActiveStrategySelection;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.port.TradingStateStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Resolves and changes the single strategy that participates in live or paper
 * scheduled decisions. Backtests can still address any enabled strategy by ID.
 */
@Component
public class TradingStrategySelectionService {
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingStateStore stateRepository;
    private final TradingStrategyMaintenance maintenance;

    public TradingStrategySelectionService(
            TradingStrategyRegistry strategyRegistry,
            TradingStateStore stateRepository
    ) {
        this(strategyRegistry, stateRepository, null);
    }

    @Autowired
    public TradingStrategySelectionService(TradingStrategyRegistry strategyRegistry, TradingStateStore stateRepository,
                                           TradingStrategyMaintenance maintenance) {
        this.strategyRegistry = strategyRegistry;
        this.stateRepository = stateRepository;
        this.maintenance = maintenance;
    }

    public ActiveStrategySelection current() {
        List<ConfiguredTradingStrategy<?>> configured = strategyRegistry.configuredStrategies();
        TradingState state = stateRepository.getState();
        if (configured.isEmpty()) {
            return selection(state);
        }

        String selectedStrategyId = state.getActiveStrategyId();
        boolean selectionExists = configured.stream()
                .anyMatch(strategy -> strategy.id().equals(selectedStrategyId));
        if (!selectionExists) {
            if (selectedStrategyId != null) throw new IllegalStateException(
                    "Selected strategy is disabled or missing; restore it or explicitly select a strategy while flat");
            state = stateRepository.selectActiveStrategy(
                    configured.getFirst().id(),
                    state.getActiveStrategyRevision()
            );
        }
        return selection(state);
    }

    public ActiveStrategySelection activate(String strategyId, Long expectedRevision) {
        ConfiguredTradingStrategy<?> configured = strategyRegistry.configuredStrategy(strategyId);
        // The shared selection adapter fences and checks exposure only when the
        // ID actually changes. Re-selecting the current ID stays idempotent even
        // with an open position, while still checking the expected revision.
        return selection(stateRepository.selectActiveStrategy(configured.id(), expectedRevision));
    }

    public <T> T withMaintenance(java.util.function.Supplier<T> action) {
        return maintenance == null ? action.get() : maintenance.change(action);
    }

    public List<ConfiguredTradingStrategy<?>> activeStrategies() {
        if (strategyRegistry.configuredStrategies().isEmpty()) {
            return List.of();
        }
        ActiveStrategySelection selection = current();
        if (selection.strategyId() == null) {
            return List.of();
        }
        return List.of(strategyRegistry.configuredStrategy(selection.strategyId()));
    }

    private static ActiveStrategySelection selection(TradingState state) {
        return new ActiveStrategySelection(
                state.getActiveStrategyId(),
                state.getActiveStrategyRevision(),
                parseInstant(state.getActiveStrategyChangedAt())
        );
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
