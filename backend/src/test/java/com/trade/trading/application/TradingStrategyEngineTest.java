package com.trade.trading.application;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.execution.TradingBroker;
import com.trade.trading.event.TradingEventPublishResult;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.strategy.StrategyConfig;
import com.trade.trading.strategy.StrategyEvaluationContext;
import com.trade.trading.strategy.TradingStrategy;
import com.trade.trading.strategy.TradingStrategyRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingStrategyEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void evaluatesOnlyThePersistedActiveStrategy() {
        TradingProperties properties = properties(List.of(
                strategyConfig("first", "hold"),
                strategyConfig("second", "buy"),
                strategyConfig("third", "buy")
        ));
        CapturingBroker broker = new CapturingBroker();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("engine-state.json"));
        repository.selectActiveStrategy("second", null);

        engine(properties, broker, repository).runDecision(TradingTrigger.scheduled());

        assertEquals(1, broker.executeCount);
        assertEquals("second", broker.lastDecision.getStrategyId());
        assertEquals(1, repository.getState().getRecentDecisions().size());
    }

    @Test
    void activeStrategyExceptionIsRecordedAsHoldWithoutRunningAnotherStrategy() {
        TradingProperties properties = properties(List.of(
                strategyConfig("first", "throw"),
                strategyConfig("second", "buy")
        ));
        CapturingBroker broker = new CapturingBroker();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("engine-error-state.json"));
        repository.selectActiveStrategy("first", null);

        engine(properties, broker, repository)
                .runDecision(TradingTrigger.scheduled());

        assertEquals(0, broker.executeCount);
        assertEquals("first", repository.getState().getRecentDecisions().getFirst().getStrategyId());
    }

    @Test
    void recordsDecisionOutcomeActionAndDurationMetrics() {
        TradingProperties properties = properties(List.of(strategyConfig("first", "buy")));
        CapturingBroker broker = new CapturingBroker();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("engine-metrics-state.json"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        engine(properties, broker, repository, meterRegistry)
                .runDecision(TradingTrigger.scheduled());

        assertEquals(1.0, meterRegistry.get("trade.trading.decisions.runs")
                .tags("trigger", "scheduled", "outcome", "completed")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("trade.trading.decisions.actions")
                .tags("action", "buy", "execution_status", "test_filled")
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("trade.trading.decisions.duration")
                .tags("trigger", "scheduled", "outcome", "completed")
                .timer()
                .count());
        assertEquals(0.0, meterRegistry.get("trade.trading.decisions.running").gauge().value());
    }

    private TradingStrategyEngine engine(
            TradingProperties properties,
            CapturingBroker broker,
            TradingStateRepository repository
    ) {
        return engine(properties, broker, repository, new SimpleMeterRegistry());
    }

    private TradingStrategyEngine engine(
            TradingProperties properties,
            CapturingBroker broker,
            TradingStateRepository repository,
            MeterRegistry meterRegistry
    ) {
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(new TestStrategy()), properties);
        TradingStrategySelectionService selectionService = new TradingStrategySelectionService(registry, repository);
        return new TradingStrategyEngine(
                new FakeMarketContextCollector(context()),
                selectionService,
                broker,
                repository,
                properties,
                new OkxMarketDataWebSocketFeed(
                        new OkxApi(new NoopOkxRestClient()),
                        properties,
                        event -> TradingEventPublishResult.ACCEPTED
                ),
                meterRegistry
        );
    }

    private static TradingDecisionContext context() {
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");
        return new TradingDecisionContext().setTicker(ticker);
    }

    private static TradingProperties properties(List<TradingProperties.StrategyInstanceProperties> strategies) {
        TradingProperties properties = new TradingProperties();
        properties.setStrategies(strategies);
        return properties;
    }

    private static TradingProperties.StrategyInstanceProperties strategyConfig(String id, String behavior) {
        TradingProperties.StrategyInstanceProperties config = new TradingProperties.StrategyInstanceProperties();
        config.setId(id);
        config.setType("test");
        config.setParams(Map.of("behavior", behavior));
        return config;
    }

    @Data
    private static class TestConfig implements StrategyConfig {
        private String behavior;
    }

    private static class TestStrategy implements TradingStrategy<TestConfig> {
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
            if ("throw".equals(config.getBehavior())) {
                throw new IllegalStateException("boom");
            }
            if ("buy".equals(config.getBehavior())) {
                return new StrategyDecision()
                        .setStrategyId(context.getStrategyId())
                        .setAction(TradingAction.BUY)
                        .setReason("buy")
                        .setBuyQuoteAmount(java.math.BigDecimal.TEN);
            }
            return StrategyDecision.hold(context.getStrategyId(), "hold");
        }
    }

    private static class CapturingBroker implements TradingBroker {
        private int executeCount;
        private StrategyDecision lastDecision;

        @Override
        public void execute(StrategyDecision decision, TradingDecisionContext context, TradingDecisionRecord decisionRecord) {
            executeCount++;
            lastDecision = decision;
            decisionRecord.setExecutionStatus("TEST_FILLED");
        }
    }

    private static class FakeMarketContextCollector extends MarketContextCollector {
        private final TradingDecisionContext context;

        FakeMarketContextCollector(TradingDecisionContext context) {
            super(null, null, null, null);
            this.context = context;
        }

        @Override
        public TradingDecisionContext collect(TradingTrigger trigger) {
            return context;
        }
    }

    private static class NoopOkxRestClient implements OkxRestClient {
        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }
    }

}
