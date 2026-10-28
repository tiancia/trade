package com.trade.trading.infrastructure.persistence;

import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.application.backtest.BacktestService;
import com.trade.trading.application.port.HistoricalCandleSource;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.port.StrategyConfigurationStore;
import com.trade.trading.application.port.TradingBroker;
import com.trade.trading.application.port.TradingMarketFeed;
import com.trade.trading.application.port.TradingMarketSource;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.application.strategy.StrategyEvaluationContext;
import com.trade.trading.application.strategy.ThresholdEventStrategy;
import com.trade.trading.application.strategy.ThresholdEventStrategyConfig;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.application.strategy.TradingStrategySelectionService;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.domain.backtest.BacktestRequest;
import com.trade.trading.domain.backtest.BacktestStatus;
import com.trade.trading.infrastructure.config.TradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(StrategyConfigurationPersistenceTest.PersistenceConfiguration.class)
@Sql(statements = {"DROP TABLE IF EXISTS okx_strategy_configuration_history", "DROP TABLE IF EXISTS okx_strategy_configuration"})
@Sql(scripts = "classpath:db/upgrade/trading/2026-10-19-strategy-configuration.sql")
class StrategyConfigurationPersistenceTest {
    @Autowired StrategyConfigurationStore store;
    @TempDir Path temporary;

    @Test void aiConfigurationFenceRejectsConcurrentEditAndRollsBackPartialWork() {
        TradingStrategyRegistry registry = registry(new TradingProperties());
        var initial = registry.configuration("threshold-event-default");
        assertThrows(IllegalStateException.class, () -> store.withUnchangedVersions(Map.of(initial.strategyId(), 1L), () -> {
            registry.updateConfiguration(initial.strategyId(), 1L, "AI pending", initial.params());
            throw new IllegalStateException("maintenance failed");
        }));
        assertEquals(1, registry.configuration(initial.strategyId()).version());
        assertEquals(1, registry.configurationHistory(initial.strategyId()).size());
        registry.updateConfiguration(initial.strategyId(), 1L, "operator edit", initial.params());
        assertThrows(ConcurrentModificationException.class, () -> store.withUnchangedVersions(Map.of(initial.strategyId(), 1L),
                () -> fail("The AI cannot apply against a changed target strategy")));
    }

    @Test
    void intradayConfigurationPersistsFullParametersAndRejectsInvalidCandidates() {
        TradingProperties properties = new TradingProperties();
        var instance = new TradingProperties.StrategyInstanceProperties();
        instance.setId("intraday");
        instance.setType("intraday-technical");
        instance.setBar("15m");
        instance.setEnabled(true);
        properties.setStrategies(List.of(instance));
        var strategy = new com.trade.trading.application.strategy.IntradayTechnicalStrategy();
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(strategy), properties, store);
        var initial = registry.configuration("intraday");
        assertEquals(23, initial.params().size());
        Map<String, Object> params = new LinkedHashMap<>(initial.params());
        params.put("mode", "VOLUME_BREAKOUT");
        params.put("entry-rsi-max", "72.123456789012345678");
        var updated = registry.updateConfiguration("intraday", initial.version(), "candidate for independent validation", params);
        assertEquals(2, updated.version());
        var restarted = new TradingStrategyRegistry(List.of(strategy), properties, store);
        assertEquals("VOLUME_BREAKOUT", restarted.configuration("intraday").params().get("mode"));
        assertEquals("VOLUME_BREAKOUT", restarted.strategySummaries().getFirst().getParams().get("mode"));
        assertEquals(23, restarted.strategySummaries().getFirst().getParams().size());
        assertDecimal("72.123456789012345678", restarted.configuration("intraday").params().get("entry-rsi-max"));
        assertThrows(ConcurrentModificationException.class, () -> restarted.updateConfiguration("intraday", 1L, "stale", params));
        params.put("rsi-period", 2.5);
        assertThrows(IllegalArgumentException.class, () -> registry.updateConfiguration("intraday", 2L, "invalid period", params));
        params.put("rsi-period", 14);
        params.remove("mode");
        assertThrows(IllegalArgumentException.class, () -> registry.updateConfiguration("intraday", 2L, "missing key", params));
        assertEquals(2, registry.configurationHistory("intraday").size());
    }

    @Test
    void materializesEffectiveDefaultsAndRetainsVersionsAcrossRegistryRestart() {
        TradingProperties properties = new TradingProperties();
        properties.setPriceMoveTriggerPercent(new BigDecimal("0.037"));
        properties.getStrategies().getFirst().getParams().clear();
        TradingStrategyRegistry registry = registry(properties);
        StrategyConfigurationVersion initial = registry.configuration("threshold-event-default");
        assertEquals(1, initial.version());
        assertDecimal("0.037", initial.params().get("price-move-trigger-percent"));
        assertDecimal("10", initial.params().get("buy-quote-amount"));
        assertDecimal("1", initial.params().get("order-size"));
        assertNull(initial.params().get("sell-base-amount"));
        assertThrows(UnsupportedOperationException.class, () -> initial.params().put("buy-quote-amount", 999));

        Map<String, Object> changed = new LinkedHashMap<>(initial.params());
        changed.put("price-move-trigger-percent", "0.012345678901234567");
        StrategyConfigurationVersion updated = registry.updateConfiguration(initial.strategyId(), 1L, "lower trigger", changed);
        changed.put("price-move-trigger-percent", "0.9");
        assertDecimal("0.037", initial.params().get("price-move-trigger-percent"));
        assertDecimal("0.012345678901234567", updated.params().get("price-move-trigger-percent"));

        TradingProperties restartedProperties = new TradingProperties();
        restartedProperties.setPriceMoveTriggerPercent(new BigDecimal("0.8"));
        TradingStrategyRegistry restarted = registry(restartedProperties);
        assertEquals(2, restarted.configuration(initial.strategyId()).version());
        assertDecimal("0.012345678901234567", restarted.configuration(initial.strategyId()).params().get("price-move-trigger-percent"));
        List<StrategyConfigurationVersion> history = restarted.configurationHistory(initial.strategyId());
        assertEquals(List.of(2L, 1L), history.stream().map(StrategyConfigurationVersion::version).toList());
        assertDecimal("0.037", history.getLast().params().get("price-move-trigger-percent"));
        assertEquals("lower trigger", history.getFirst().reason());
    }

    @Test
    void concurrentWritersCannotOverwriteOneAnotherOrAppendARejectedVersion() throws Exception {
        TradingStrategyRegistry first = registry(new TradingProperties());
        TradingStrategyRegistry second = registry(new TradingProperties());
        StrategyConfigurationVersion initial = first.configuration("threshold-event-default");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (TradingStrategyRegistry writer : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        writer.updateConfiguration(initial.strategyId(), 1L, "concurrent edit", initial.params());
                        return true;
                    } catch (ConcurrentModificationException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var result : results) { if (result.get(10, TimeUnit.SECONDS)) { successful++; } }
            assertEquals(1, successful);
        }
        assertEquals(List.of(2L, 1L), first.configurationHistory(initial.strategyId()).stream()
                .map(StrategyConfigurationVersion::version).toList());
    }

    @Test
    void rejectsUnknownKeysCoercedBooleansFractionalWindowsAndOutOfRangeValues() {
        TradingStrategyRegistry registry = registry(new TradingProperties());
        StrategyConfigurationVersion initial = registry.configuration("threshold-event-default");
        for (Map.Entry<String, Object> invalid : List.<Map.Entry<String, Object>>of(
                Map.entry("unknown-key", 1), Map.entry("require-confirmed-candle", "true"),
                Map.entry("price-move-window-candles", 2.5), Map.entry("volume-lookback-candles", 1001),
                Map.entry("price-move-trigger-percent", 1.1), Map.entry("buy-quote-amount", 0),
                Map.entry("order-size", "NaN"))) {
            Map<String, Object> params = new LinkedHashMap<>(initial.params());
            params.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class,
                    () -> registry.updateConfiguration(initial.strategyId(), 1L, "invalid edit", params), invalid.getKey());
        }
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateConfiguration(initial.strategyId(), null, "edit", initial.params()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateConfiguration(initial.strategyId(), 1L, " ", initial.params()));
        assertEquals(1, registry.configurationHistory(initial.strategyId()).size());
    }

    @Test
    void rejectsWindowsBeyondCollectedCandlesAndPaginatesVersionHistory() {
        TradingStrategyRegistry registry = registry(new TradingProperties());
        StrategyConfigurationVersion initial = registry.configuration("threshold-event-default");
        Map<String, Object> params = new LinkedHashMap<>(initial.params());
        params.put("price-move-window-candles", 25);
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateConfiguration(initial.strategyId(), 1L, "too many candles", params));
        params.put("price-move-window-candles", 24);
        params.put("volume-lookback-candles", 24);
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateConfiguration(initial.strategyId(), 1L, "too much history", params));
        params.put("volume-lookback-candles", 23);
        assertEquals(2, registry.updateConfiguration(initial.strategyId(), 1L, "within collected history", params).version());
        assertEquals(1, registry.configurationHistory(initial.strategyId(), 1, 1).getFirst().version());
        assertThrows(IllegalArgumentException.class, () -> registry.configurationHistory(initial.strategyId(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> registry.configurationHistory(initial.strategyId(), 0, 501));
    }

    @Test
    void inFlightDecisionRetainsOriginalVersionWhileNextDecisionUsesUpdatedParameters() {
        TradingProperties properties = new TradingProperties();
        AtomicReference<TradingStrategyRegistry> registryRef = new AtomicReference<>();
        List<BigDecimal> observedThresholds = new ArrayList<>();
        ThresholdEventStrategy strategy = new ThresholdEventStrategy() {
            @Override public StrategyDecision evaluate(StrategyEvaluationContext context, ThresholdEventStrategyConfig config) {
                observedThresholds.add(config.getPriceMoveTriggerPercent());
                if (observedThresholds.size() == 1) {
                    StrategyConfigurationVersion current = registryRef.get().configuration(context.getStrategyId());
                    Map<String, Object> next = new LinkedHashMap<>(current.params());
                    next.put("price-move-trigger-percent", "0.03");
                    registryRef.get().updateConfiguration(context.getStrategyId(), current.version(), "during evaluation", next);
                }
                return StrategyDecision.hold(context.getStrategyId(), "test decision");
            }
        };
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(strategy), properties, store);
        registryRef.set(registry);
        TradingStateRepository state = new TradingStateRepository(temporary.resolve("decisions.json"));
        TradingMarketSource market = mock(TradingMarketSource.class);
        TickerResp ticker = new TickerResp();
        ticker.setLast("100");
        when(market.collect(any())).thenReturn(new TradingDecisionContext().setTicker(ticker));
        TradingStrategyEngine engine = new TradingStrategyEngine(market,
                new TradingStrategySelectionService(registry, state), mock(TradingBroker.class), state, properties,
                mock(TradingMarketFeed.class), mock(FundSafetyService.class), mock(OrderReconciliationService.class),
                mock(TradingLeadershipService.class), new SimpleMeterRegistry(), mock(PaperAccountService.class));
        assertTrue(engine.runDecision(TradingTrigger.scheduled()));
        assertTrue(engine.runDecision(TradingTrigger.scheduled()));
        var decisions = new TradingStateRepository(temporary.resolve("decisions.json")).getState().getRecentDecisions();
        assertEquals(List.of(2L, 1L), decisions.stream().map(decision -> decision.getStrategyConfigVersion()).toList());
        assertDecimal("0.03", decisions.getFirst().getStrategyConfigSnapshot().get("price-move-trigger-percent"));
        assertDecimal("0.02", decisions.getLast().getStrategyConfigSnapshot().get("price-move-trigger-percent"));
        assertEquals(List.of(new BigDecimal("0.02"), new BigDecimal("0.03")), observedThresholds);
    }

    @Test
    void queuedBacktestKeepsItsVersionAndParametersAfterAnOperatorEdit() {
        TradingProperties properties = new TradingProperties();
        List<BigDecimal> evaluated = new ArrayList<>();
        ThresholdEventStrategy strategy = new ThresholdEventStrategy() {
            @Override public StrategyDecision evaluate(StrategyEvaluationContext context, ThresholdEventStrategyConfig config) {
                evaluated.add(config.getPriceMoveTriggerPercent());
                return StrategyDecision.hold(context.getStrategyId(), "version test");
            }
        };
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(strategy), properties, store);
        StrategyConfigurationVersion initial = registry.configuration("threshold-event-default");
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(candle("2026-10-19T00:00:00Z"), candle("2026-10-19T00:01:00Z")));
        List<Runnable> queued = new ArrayList<>();
        BacktestService service = new BacktestService(history, registry, properties, queued::add);
        BacktestRequest request = new BacktestRequest().setStrategyId(initial.strategyId()).setInstId("BTC-USDT")
                .setBar("1m").setFrom(Instant.parse("2026-10-19T00:00:00Z"))
                .setTo(Instant.parse("2026-10-19T00:02:00Z"));
        var oldRun = service.start(request);
        Map<String, Object> next = new LinkedHashMap<>(initial.params());
        next.put("price-move-trigger-percent", "0.03");
        registry.updateConfiguration(initial.strategyId(), 1L, "after enqueue", next);
        queued.removeFirst().run();
        assertEquals(BacktestStatus.SUCCEEDED, oldRun.getStatus());
        assertEquals(1, oldRun.getStrategyConfigVersion());
        assertDecimal("0.02", oldRun.getStrategyConfigSnapshot().get("price-move-trigger-percent"));
        assertThrows(UnsupportedOperationException.class,
                () -> oldRun.getStrategyConfigSnapshot().put("price-move-trigger-percent", 0.9));
        var newRun = service.start(request);
        queued.removeFirst().run();
        assertEquals(2, newRun.getStrategyConfigVersion());
        assertDecimal("0.03", newRun.getStrategyConfigSnapshot().get("price-move-trigger-percent"));
        assertEquals(List.of(new BigDecimal("0.02"), new BigDecimal("0.03")), evaluated);
    }

    private static CandleResp candle(String timestamp) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(Instant.parse(timestamp).toEpochMilli()));
        candle.setOpen("100"); candle.setClose("100"); candle.setHigh("100"); candle.setLow("100");
        candle.setConfirm("1"); candle.setVolCcyQuote("100");
        return candle;
    }

    private TradingStrategyRegistry registry(TradingProperties properties) {
        return new TradingStrategyRegistry(List.of(new ThresholdEventStrategy()), properties, store);
    }

    private static void assertDecimal(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceConfiguration {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:strategy_configuration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/trading/StrategyConfigurationMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<StrategyConfigurationMapper> mapper(SqlSessionFactory factory) {
            MapperFactoryBean<StrategyConfigurationMapper> mapper = new MapperFactoryBean<>(StrategyConfigurationMapper.class);
            mapper.setSqlSessionFactory(factory); return mapper;
        }
        @Bean StrategyConfigurationStore configurationStore(StrategyConfigurationMapper mapper) {
            return new MyBatisStrategyConfigurationStore(mapper);
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
