package com.trade.trading.application;

import com.trade.trading.model.TradingState;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.strategy.ConfiguredTradingStrategy;
import com.trade.trading.strategy.TradingStrategyRegistry;
import org.springframework.stereotype.Component;

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
    private final TradingStateRepository stateRepository;

    public TradingStrategySelectionService(
            TradingStrategyRegistry strategyRegistry,
            TradingStateRepository stateRepository
    ) {
        this.strategyRegistry = strategyRegistry;
        this.stateRepository = stateRepository;
    }

    public synchronized ActiveStrategySelection current() {
        List<ConfiguredTradingStrategy<?>> configured = strategyRegistry.configuredStrategies();
        TradingState state = stateRepository.getState();
        if (configured.isEmpty()) {
            return selection(state);
        }

        String selectedStrategyId = state.getActiveStrategyId();
        boolean selectionExists = configured.stream()
                .anyMatch(strategy -> strategy.id().equals(selectedStrategyId));
        if (!selectionExists) {
            state = stateRepository.selectActiveStrategy(
                    configured.getFirst().id(),
                    state.getActiveStrategyRevision()
            );
        }
        return selection(state);
    }

    public synchronized ActiveStrategySelection activate(String strategyId, Long expectedRevision) {
        ConfiguredTradingStrategy<?> configured = strategyRegistry.configuredStrategy(strategyId);
        return selection(stateRepository.selectActiveStrategy(configured.id(), expectedRevision));
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
