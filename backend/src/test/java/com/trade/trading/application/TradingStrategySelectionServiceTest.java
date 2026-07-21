package com.trade.trading.application;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.strategy.StrategyConfig;
import com.trade.trading.strategy.StrategyEvaluationContext;
import com.trade.trading.strategy.TradingStrategy;
import com.trade.trading.strategy.TradingStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingStrategySelectionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsToFirstEnabledStrategyAndPersistsOperatorChanges() {
        TradingProperties properties = new TradingProperties();
        properties.setStrategies(List.of(strategy("balanced", true), strategy("defensive", true)));
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("selection.json"));
        TradingStrategySelectionService service = new TradingStrategySelectionService(
                new TradingStrategyRegistry(List.of(new TestStrategy()), properties),
                repository
        );

        ActiveStrategySelection initial = service.current();
        assertEquals("balanced", initial.strategyId());
        assertEquals(1L, initial.revision());

        ActiveStrategySelection changed = service.activate("defensive", initial.revision());
        assertEquals("defensive", changed.strategyId());
        assertEquals(2L, changed.revision());
        assertEquals("defensive", service.activeStrategies().getFirst().id());

        assertThrows(ConcurrentModificationException.class,
                () -> service.activate("balanced", initial.revision()));
    }

    @Test
    void rejectsDisabledOrUnknownStrategies() {
        TradingProperties properties = new TradingProperties();
        properties.setStrategies(List.of(strategy("disabled", false)));
        TradingStrategySelectionService service = new TradingStrategySelectionService(
                new TradingStrategyRegistry(List.of(new TestStrategy()), properties),
                new TradingStateRepository(tempDir.resolve("disabled.json"))
        );

        assertThrows(IllegalArgumentException.class, () -> service.activate("disabled", null));
    }

    private static TradingProperties.StrategyInstanceProperties strategy(String id, boolean enabled) {
        TradingProperties.StrategyInstanceProperties value = new TradingProperties.StrategyInstanceProperties();
        value.setId(id);
        value.setType("test");
        value.setEnabled(enabled);
        return value;
    }

    private static final class TestConfig implements StrategyConfig {
    }

    private static final class TestStrategy implements TradingStrategy<TestConfig> {
        @Override
        public String type() {
            return "test";
        }

        @Override
        public Class<TestConfig> configType() {
            return TestConfig.class;
        }

        @Override
        public StrategyDecision evaluate(StrategyEvaluationContext context, TestConfig config) {
            return StrategyDecision.hold(context.getStrategyId(), "test");
        }
    }
}
