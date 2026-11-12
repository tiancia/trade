package com.trade.trading.application.strategy;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.event.TradingEventPublishResult;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.port.TradingBroker;
import com.trade.trading.port.TradingMarketSource;
import com.trade.trading.port.TradingMarketFeed;
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.risk.RiskRule;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.market.MarketContextCollector;
import com.trade.trading.infrastructure.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TradingStrategyEngineTest {
    private static final Clock RISK_CLOCK = Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void runtimePublishesTheConfiguredInstrumentForMarketAndBacktestDefaults() {
        TradingProperties properties = new TradingProperties();
        properties.setInstId("ETH-USDC");
        properties.setBaseCcy("ETH");
        properties.setQuoteCcy("USDC");
        TradingStrategyEngine engine = new TradingStrategyEngine(mock(TradingMarketSource.class),
                mock(TradingStrategySelectionService.class), mock(TradingBroker.class), mock(TradingStateStore.class),
                properties, mock(TradingMarketFeed.class), mock(FundSafetyService.class), mock(OrderReconciliationService.class),
                mock(TradingLeadershipService.class), new SimpleMeterRegistry(), mock(PaperAccountService.class), mock(RiskControlService.class));
        var status = engine.status();
        assertEquals("ETH-USDC", status.getInstId());
        assertEquals("ETH", status.getBaseCcy());
        assertEquals("USDC", status.getQuoteCcy());
    }

    @Test
    void backtestModeSkipsRealtimeCollectionSelectionAndExecution() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.BACKTEST);
        TradingMarketSource market = mock(TradingMarketSource.class);
        TradingStrategySelectionService selection = mock(TradingStrategySelectionService.class);
        TradingBroker broker = mock(TradingBroker.class);
        TradingStateStore state = mock(TradingStateStore.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        TradingStrategyEngine engine = new TradingStrategyEngine(
                market, selection, broker, state, properties, mock(TradingMarketFeed.class),
                mock(FundSafetyService.class), mock(OrderReconciliationService.class),
                mock(TradingLeadershipService.class), metrics, mock(PaperAccountService.class), mock(RiskControlService.class));

        assertFalse(engine.runDecision(TradingTrigger.scheduled()));

        verifyNoInteractions(market, selection, broker, state);
        assertEquals(1.0, metrics.get("trade.trading.decisions.runs")
                .tags("trigger", "scheduled", "outcome", "unsupported_mode").counter().count());
    }

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
    void activeStrategyExceptionFailsTheRunWithoutRunningAnotherStrategy() {
        TradingProperties properties = properties(List.of(
                strategyConfig("first", "throw"),
                strategyConfig("second", "buy")
        ));
        CapturingBroker broker = new CapturingBroker();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("engine-error-state.json"));
        repository.selectActiveStrategy("first", null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> engine(properties, broker, repository).runDecision(TradingTrigger.scheduled()));

        assertEquals(0, broker.executeCount);
        org.junit.jupiter.api.Assertions.assertTrue(repository.getState().getRecentDecisions().isEmpty());
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

    @ParameterizedTest
    @ValueSource(strings = {"RISK_MAX_DRAWDOWN", "RISK_DAILY_LOSS"})
    void holdSamplesEquityBeforeTheNextOpeningDecision(String rule) {
        TradingProperties properties = properties(List.of(strategyConfig("first", "hold")));
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.getRisk().setMaxDrawdownRatio("RISK_MAX_DRAWDOWN".equals(rule)
                ? new BigDecimal("0.05") : BigDecimal.ZERO);
        properties.getRisk().setMaxDailyLossRatio("RISK_DAILY_LOSS".equals(rule)
                ? new BigDecimal("0.05") : BigDecimal.ZERO);
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("hold-risk.json"));
        repository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17"));
        AccountBalanceResp account = new AccountBalanceResp();
        account.setTotalEq("1200");
        TradingDecisionContext context = context().setAccountBalance(account);
        CapturingBroker broker = new CapturingBroker();

        assertTrue(engine(properties, broker, repository, new SimpleMeterRegistry(), context)
                .runDecision(TradingTrigger.scheduled()));

        assertEquals(0, broker.executeCount);
        assertEquals("HELD", repository.getState().getRecentDecisions().getFirst().getExecutionStatus());
        RiskControlService risk = new RiskControlService(properties, repository, RISK_CLOCK, RiskRule.standardRules());
        account.setTotalEq("1080");
        var buy = risk.evaluate(StrategyDecision.buy("first", BigDecimal.TEN, "add"), context);
        assertFalse(buy.isAllowed());
        assertTrue(buy.skipReason().startsWith(rule + ":"), buy.skipReason());
        assertTrue(risk.evaluate(new StrategyDecision().setAction(TradingAction.SELL), context).isAllowed());
        assertEquals(0, new BigDecimal("1200").compareTo(repository.getState().getRiskState().getEquityHighWatermark()));
        assertEquals("2026-05-18", repository.getState().getRiskState().getDayStartDate());
        assertEquals(0, repository.getState().getRiskState().getConsecutiveOpenActions());
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
        return engine(properties, broker, repository, meterRegistry, context());
    }

    private TradingStrategyEngine engine(
            TradingProperties properties,
            CapturingBroker broker,
            TradingStateRepository repository,
            MeterRegistry meterRegistry,
            TradingDecisionContext marketContext
    ) {
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(new TestStrategy()), properties);
        TradingStrategySelectionService selectionService = new TradingStrategySelectionService(registry, repository);
        return new TradingStrategyEngine(
                new FakeMarketContextCollector(marketContext),
                selectionService,
                broker,
                repository,
                properties,
                new OkxMarketDataWebSocketFeed(
                        new OkxApi(new NoopOkxRestClient()),
                        properties,
                        event -> TradingEventPublishResult.ACCEPTED
                ),
                mock(FundSafetyService.class),
                mock(OrderReconciliationService.class),
                mock(TradingLeadershipService.class),
                meterRegistry,
                mock(PaperAccountService.class),
                new RiskControlService(properties, repository, RISK_CLOCK, RiskRule.standardRules())
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
            super(null, null, null, null, null);
            this.context = context;
        }

        @Override
        public TradingDecisionContext collect(TradingTrigger trigger) {
            return context;
        }
        @Override
        public TradingDecisionContext collect(TradingTrigger trigger, String bar) { return context; }
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
