package com.trade.trading.infrastructure.persistence;

import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderSettlementService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.application.risk.RiskInputs;
import com.trade.trading.application.strategy.TradingStrategyMaintenance;
import com.trade.trading.domain.model.*;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TradingRuntimeSafetyPersistenceTest.PersistenceConfiguration.class)
class TradingRuntimeSafetyPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    @Autowired DataSource dataSource;
    @Autowired StrategySelectionStore selections;
    @Autowired StrategyConfigurationStore configurations;
    @Autowired TradingFinancialStateStore financial;
    @Autowired PaperAccountStore paper;
    @Autowired TradingStrategyMaintenance maintenance;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderSettlementService settlement;
    @Autowired TradingProperties properties;
    @TempDir Path temporary;

    @BeforeEach void schema() throws Exception {
        properties.setExecutionMode(ExecutionMode.LIVE);
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().execute("DROP ALL OBJECTS");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema/trading/schema.sql"));
        }
    }

    @Test void stalePaperPriceRollsBackBeforeBeingReportedAsANormalSkip() {
        var paperProperties = new TradingProperties();
        selections.currentOrCreate("paper", "BTC-USDT", new ActiveStrategySelection("first", 1, NOW));
        configurations.currentOrCreate(baseline());
        var account = paper.start("BTC-USDT", "USDT", new BigDecimal("100"), null, null, NOW);
        var service = new com.trade.trading.application.paper.PaperAccountService(paper, paperProperties, Clock.fixed(NOW, ZoneOffset.UTC));
        var ticker = new com.trade.client.okx.dto.TickerResp(); ticker.setLast("10");
        ticker.setTs(Long.toString(NOW.minusSeconds(121).toEpochMilli()));
        var context = service.enrichContext(new com.trade.trading.application.market.TradingDecisionContext()
                .setTicker(ticker).setInstrument(new com.trade.client.okx.dto.InstrumentInfoResp()));
        var risk = new RiskControlService(paperProperties, org.mockito.Mockito.mock(TradingStateStore.class),
                Clock.fixed(NOW, ZoneOffset.UTC), com.trade.trading.domain.risk.RiskRule.standardRules(), service);
        var executor = new com.trade.trading.application.execution.PaperOrderExecutionService(
                new com.trade.trading.application.execution.OrderSizingService(paperProperties), paperProperties, risk, service,
                new TradingStrategyMaintenance(selections, configurations, paperProperties));
        var record = decision(1, 1).setDecisionId("stale-price").setStrategyConfigSnapshot(Map.of());
        assertDoesNotThrow(() -> executor.execute(StrategyDecision.buy("first", BigDecimal.TEN, "test"), context, record));
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().contains("stale"));
        assertEquals(0, paper.active("BTC-USDT").version());
        assertTrue(paper.fills("BTC-USDT", account.sessionId(), null, 10).isEmpty());
    }

    @Test void selectionIsSharedEvenWithDifferentCachedLegacyFilesAndRejectsStaleRevision() throws Exception {
        Path firstFile = temporary.resolve("first.json"), secondFile = temporary.resolve("second.json");
        Files.writeString(firstFile, "{\"activeStrategyId\":\"first\",\"activeStrategyRevision\":4}");
        Files.writeString(secondFile, "{\"activeStrategyId\":\"conflicting-legacy\",\"activeStrategyRevision\":99}");
        var first = new TradingStateRepository(firstFile, financial, "live", "BTC-USDT", selections);
        var second = new TradingStateRepository(secondFile, financial, "live", "BTC-USDT", selections);
        assertEquals("first", first.getState().getActiveStrategyId());
        assertEquals("first", second.getState().getActiveStrategyId());
        first.selectActiveStrategy("second", 4L);
        assertEquals("second", second.getState().getActiveStrategyId());
        assertEquals(5, second.getState().getActiveStrategyRevision());
        assertThrows(ConcurrentModificationException.class, () -> second.selectActiveStrategy("first", 4L));
        assertTrue(Files.readString(firstFile).contains("\"first\""));
    }

    @Test void upgradePreservesExistingSellCheckpointsAndAddsRiskColumns() throws Exception {
        var order = reserve("legacy-sell", "SELL");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE okx_risk_state DROP COLUMN open_trade_realized_pnl");
        jdbc.execute("ALTER TABLE okx_risk_state DROP COLUMN closed_trade_accounting_version");
        jdbc.update("INSERT INTO okx_order_fill_ledger (order_id,side,cumulative_filled_size,applied_position_quantity,"
                + "applied_quote_cost,average_fill_price,fee,fee_ccy,exchange_state,version,created_at,updated_at) "
                + "VALUES (?,'sell',1,1,0,90,-2,'USDT','filled',1,?,?)", order.getId(), NOW, NOW);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/upgrade/trading/2026-10-29-trading-runtime-safety.sql"));
        }
        assertDecimal("88", jdbc.queryForObject("SELECT applied_quote_cost FROM okx_order_fill_ledger WHERE order_id = ?",
                BigDecimal.class, order.getId()));
        assertFalse(financial.applyCumulativeSpotFill(order.getId(), "live", "BTC-USDT", "sell", BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("88"), new BigDecimal("90"), new BigDecimal("-2"), "USDT", "filled", NOW).changed());
        assertEquals(0, risk().getClosedTradeAccountingVersion());
    }

    @Test void livePositionsPendingOrdersAndPaperPositionsAllBlockMaintenance() {
        var selected = initializeSelection();
        financial.recordBuy("live", "BTC-USDT", BigDecimal.ONE, new BigDecimal("100"));
        assertEquals(selected, selections.select("live", "BTC-USDT", "first", selected.revision()));
        assertThrows(ConcurrentModificationException.class,
                () -> selections.select("live", "BTC-USDT", "second", selected.revision()));
        assertThrows(ConcurrentModificationException.class, () -> maintenance.change(() -> true));
        financial.recordSell("live", "BTC-USDT", BigDecimal.ONE);
        var pending = reserve("pending", "BUY");
        assertThrows(ConcurrentModificationException.class, () -> maintenance.change(() -> true));
        lifecycle.markSubmissionBlocked(pending.getIdempotencyKey(), "TEST", "no exchange submission");
        var account = paper.start("BTC-USDT", "USDT", new BigDecimal("100"), null, null, NOW);
        paper.fill(new PaperAccountStore.FillCommand("BTC-USDT", account.sessionId(), account.version(), "paper-buy",
                "first", 1, "{}", "BUY", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, NOW, NOW,
                RiskInputs.policy(properties.getRisk())));
        assertThrows(ConcurrentModificationException.class, () -> maintenance.change(() -> true));
    }

    @Test void changedSelectionAndConfigurationFenceAlreadyCollectedDecisions() {
        var selected = initializeSelection();
        var record = decision(selected.revision(), 1);
        selections.select("live", "BTC-USDT", "second", selected.revision());
        assertThrows(ConcurrentModificationException.class,
                () -> maintenance.execute(record, () -> fail("Stale selection must not reserve an order")));
        var current = selections.select("live", "BTC-USDT", "first", selected.revision() + 1);
        record.setActiveStrategyRevision(current.revision());
        configurations.update(1, new StrategyConfigurationVersion("first", "test", "1m", 2,
                NOW.toString(), "operator edit", Map.of()));
        assertThrows(ConcurrentModificationException.class,
                () -> maintenance.execute(record, () -> fail("Stale parameters must not reserve an order")));
        assertEquals(0, new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM okx_orders", Integer.class));
    }

    @Test void reservationAndMaintenanceSerializeAcrossThreadsBeforeAnyExchangeIo() throws Exception {
        var selected = initializeSelection();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var maintenanceStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var submission = workers.submit(() -> maintenance.execute(decision(selected.revision(), 1), () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return reserve("in-flight", "BUY");
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var change = workers.submit(() -> {
                maintenanceStarted.countDown();
                return maintenance.change(() -> configurations.update(1, new StrategyConfigurationVersion(
                        "first", "test", "1m", 2, NOW.toString(), "must be rejected", Map.of())));
            });
            assertTrue(maintenanceStarted.await(5, TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> change.get(100, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(OrderStatus.SUBMITTING, submission.get(5, TimeUnit.SECONDS).getStatus());
            assertInstanceOf(ConcurrentModificationException.class,
                    assertThrows(ExecutionException.class, () -> change.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, configurations.currentOrCreate(baseline()).version());
        } finally { release.countDown(); }
    }

    @Test void livePartialFillPnlCountsOneFullNetLosingTradeAndReplayDoesNotCountAgain() {
        fill("buy", "BUY", "1", "100", "0", "filled");
        var sell = reserve("sell", "SELL");
        settlement.applyExchangeSnapshot(sell, observation(sell, "0.4", "90", "-1", "partially_filled"));
        assertEquals(0, risk().getConsecutiveLosses());
        assertDecimal("-5", risk().getOpenTradeRealizedPnl());
        var finished = settlement.applyExchangeSnapshot(lifecycle.find("sell").orElseThrow(),
                observation(sell, "1", "90", "-2", "filled"));
        assertEquals(1, risk().getConsecutiveLosses());
        assertNull(risk().getOpenTradeRealizedPnl());
        settlement.applyExchangeSnapshot(finished.order(), observation(sell, "1", "90", "-2", "filled"));
        assertEquals(1, risk().getConsecutiveLosses());
        fill("next-buy", "BUY", "1", "100", "0", "filled");
        fill("winning-close", "SELL", "1", "110", "-1", "filled");
        assertEquals(0, risk().getConsecutiveLosses());
    }

    @Test void failedClosedTradeRiskWriteRollsBackOrderFillAndPositionTogether() {
        fill("first-buy", "BUY", "1", "100", "0", "filled");
        fill("first-loss", "SELL", "1", "90", "0", "filled");
        fill("second-buy", "BUY", "1", "100", "0", "filled");
        new JdbcTemplate(dataSource).execute("ALTER TABLE okx_risk_state ADD CONSTRAINT reject_second_loss CHECK (consecutive_losses < 2)");
        var sell = reserve("second-loss", "SELL");
        assertThrows(RuntimeException.class,
                () -> settlement.applyExchangeSnapshot(sell, observation(sell, "1", "90", "0", "filled")));
        assertEquals(OrderStatus.SUBMITTING, lifecycle.find("second-loss").orElseThrow().getStatus());
        assertDecimal("1", financial.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO, BigDecimal.ZERO).getQuantity());
        assertEquals(1, risk().getConsecutiveLosses());
        assertEquals(0, new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM okx_order_fill_ledger WHERE order_id = ?", Integer.class, sell.getId()));
    }

    private ActiveStrategySelection initializeSelection() {
        selections.currentOrCreate("live", "BTC-USDT", new ActiveStrategySelection(null, 0, null));
        configurations.currentOrCreate(baseline());
        return selections.select("live", "BTC-USDT", "first", 0L);
    }
    private static StrategyConfigurationVersion baseline() {
        return new StrategyConfigurationVersion("first", "test", "1m", 1, NOW.toString(), "test", Map.of());
    }
    private static TradingDecisionRecord decision(long revision, long version) {
        return new TradingDecisionRecord().setStrategyId("first").setActiveStrategyRevision(revision).setStrategyConfigVersion(version);
    }
    private com.trade.trading.domain.order.TradingOrder reserve(String key, String action) {
        return lifecycle.reserve(new OrderSubmission(key, "client" + key.replace("-", ""), key, "first", "BTC-USDT", action,
                action.equals("BUY") ? "buy" : "sell", "cash", "market", action.equals("BUY") ? "quote_ccy" : "base_ccy",
                action.equals("BUY") ? new BigDecimal("100") : BigDecimal.ONE)).order();
    }
    private void fill(String key, String action, String quantity, String price, String fee, String state) {
        var order = reserve(key, action);
        settlement.applyExchangeSnapshot(order, observation(order, quantity, price, fee, state));
    }
    private static OrderInfoResp observation(com.trade.trading.domain.order.TradingOrder order,
                                             String quantity, String price, String fee, String state) {
        var observed = new OrderInfoResp();
        observed.setOrdId("exchange" + order.getId()); observed.setClOrdId(order.getClientOrderId());
        observed.setInstId("BTC-USDT"); observed.setSide(order.getSide()); observed.setState(state);
        observed.setAccFillSz(quantity); observed.setAvgPx(price); observed.setFee(fee); observed.setFeeCcy("USDT");
        observed.setUTime(Long.toString(NOW.toEpochMilli())); return observed;
    }
    private TradingRiskState risk() { return financial.getOrCreateRiskState("live", new TradingRiskState()); }
    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class PersistenceConfiguration {
        @Bean DataSource dataSource() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:trading_runtime_safety;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/trading/StrategySelectionMapper.xml"),
                    new ClassPathResource("mapper/trading/StrategyConfigurationMapper.xml"),
                    new ClassPathResource("mapper/trading/TradingFinancialStateMapper.xml"),
                    new ClassPathResource("mapper/trading/TradingOrderMapper.xml"),
                    new ClassPathResource("mapper/trading/PaperAccountMapper.xml"));
            var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config); return factory.getObject();
        }
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(type); mapper.setSqlSessionFactory(factory); return mapper;
        }
        @Bean MapperFactoryBean<StrategySelectionMapper> selectionMapper(SqlSessionFactory f) { return mapper(StrategySelectionMapper.class, f); }
        @Bean MapperFactoryBean<StrategyConfigurationMapper> configurationMapper(SqlSessionFactory f) { return mapper(StrategyConfigurationMapper.class, f); }
        @Bean MapperFactoryBean<TradingFinancialStateMapper> financialMapper(SqlSessionFactory f) { return mapper(TradingFinancialStateMapper.class, f); }
        @Bean MapperFactoryBean<TradingOrderMapper> orderMapper(SqlSessionFactory f) { return mapper(TradingOrderMapper.class, f); }
        @Bean MapperFactoryBean<PaperAccountMapper> paperMapper(SqlSessionFactory f) { return mapper(PaperAccountMapper.class, f); }
        @Bean StrategySelectionStore selections(StrategySelectionMapper m) { return new MyBatisStrategySelectionStore(m); }
        @Bean StrategyConfigurationStore configurations(StrategyConfigurationMapper m) { return new MyBatisStrategyConfigurationStore(m); }
        @Bean TradingFinancialStateStore financial(TradingFinancialStateMapper m) { return new MyBatisTradingFinancialStateStore(m); }
        @Bean PaperAccountStore paper(PaperAccountMapper m) { return new MyBatisPaperAccountStore(m); }
        @Bean TradingOrderRepository orders(TradingOrderMapper m) { return new MyBatisTradingOrderRepository(m); }
        @Bean TradingProperties properties() { var p = new TradingProperties(); p.setExecutionMode(ExecutionMode.LIVE); return p; }
        @Bean TradingStateStore state(TradingProperties p, TradingFinancialStateStore f, StrategySelectionStore s) throws Exception {
            return new TradingStateRepository(Files.createTempDirectory("trading-runtime-safety-").resolve("memory.json"), f, "live", p.getInstId(), s);
        }
        @Bean TradingStrategyMaintenance maintenance(StrategySelectionStore s, StrategyConfigurationStore c, TradingProperties p) {
            return new TradingStrategyMaintenance(s, c, p);
        }
        @Bean OrderLifecycleService lifecycle(TradingOrderRepository r) { return new OrderLifecycleService(r, new SimpleMeterRegistry()); }
        @Bean RiskControlService risk(TradingProperties p, TradingStateStore s) {
            return new RiskControlService(p, s, Clock.fixed(NOW, ZoneOffset.UTC), com.trade.trading.domain.risk.RiskRule.standardRules());
        }
        @Bean OrderSettlementService settlement(OrderLifecycleService l, TradingStateStore s, RiskControlService r, TradingProperties p) {
            return new OrderSettlementService(l, s, r, p);
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
    }
}
