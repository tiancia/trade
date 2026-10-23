package com.trade.trading.infrastructure.persistence;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.execution.OrderSizingService;
import com.trade.trading.application.execution.PaperOrderExecutionService;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.port.PaperAccountStore;
import com.trade.trading.application.port.TradingStateStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.config.TradingRiskConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline production Mapper + transaction slice, including simultaneous writers. */
@SpringJUnitConfig(MyBatisPaperAccountStoreTest.Config.class)
class MyBatisPaperAccountStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");
    @Autowired PaperAccountStore store;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void schema() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS okx_paper_equity");
        jdbc.execute("DROP TABLE IF EXISTS okx_paper_fills");
        jdbc.execute("DROP TABLE IF EXISTS okx_paper_account_heads");
        jdbc.execute("DROP TABLE IF EXISTS okx_paper_sessions");
        String ddl;
        try (var input = new ClassPathResource("db/upgrade/trading/migration_add_okx_paper_accounts.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll(
                    "(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
        }
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(dataSource);
    }

    @Test void buySellFeesEquityAndReplayStayDurableAcrossStoreInstances() {
        PaperAccount a = start("1000");
        var buy = store.fill(command(a, "buy", "BUY", "100", "10", "0.01"));
        decimal("900", buy.account().cash()); decimal("9.9", buy.account().positionQuantity());
        decimal("100", buy.account().positionCost()); decimal("999", buy.account().equity());
        decimal("1", buy.account().totalFeesQuote());
        assertTrue(store.fill(command(a, "buy", "BUY", "100", "10", "0.01")).replay());
        assertEquals(1, store.fills("BTC-USDT", a.sessionId(), null, 100).size());
        var marked = store.mark("BTC-USDT", a.sessionId(), new BigDecimal("12"), NOW,
                "threshold", 1L, NOW);
        decimal("1018.8", marked.equity()); decimal("18.8", marked.unrealizedPnl());
        var sell = store.fill(command(marked, "sell", "SELL", "9.9", "12", "0.01"));
        decimal("1017.612", sell.account().cash()); decimal("0", sell.account().positionQuantity());
        decimal("0", sell.account().positionCost()); decimal("17.612", sell.account().realizedPnl());
        decimal("2.188", sell.account().totalFeesQuote());
        var persisted = store.session("BTC-USDT", a.sessionId());
        decimal("1017.612", persisted.equity()); assertEquals(2, persisted.version());
        var fills = store.fills("BTC-USDT", a.sessionId(), null, 1);
        assertEquals("sell", fills.getFirst().decisionId()); assertNotNull(fills.getFirst().id());
        assertEquals("buy", store.fills("BTC-USDT", a.sessionId(), fills.getFirst().id(), 1).getFirst().decisionId());
        assertEquals(1L, fills.getFirst().strategyConfigVersion());
        assertEquals("{\"params\":{\"lookback\":5}}", fills.getFirst().strategyConfigSnapshotJson());
        assertEquals(4, store.equity("BTC-USDT", a.sessionId(), null, 100).size());
    }

    @Test void insufficientFundsOversellAndMissingOrStalePriceRollbackEverything() {
        PaperAccount a = start("100");
        assertThrows(IllegalArgumentException.class, () -> store.fill(command(a, "no-cash", "BUY", "101", "10", "0.01")));
        assertThrows(IllegalArgumentException.class, () -> store.fill(command(a, "oversell", "SELL", "1", "10", "0.01")));
        var stale = new PaperAccountStore.FillCommand("BTC-USDT", a.sessionId(), a.version(), "stale", "threshold", 1,
                "{}", "BUY", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, NOW.minusSeconds(121), NOW);
        assertThrows(IllegalArgumentException.class, () -> store.fill(stale));
        decimal("100", store.active("BTC-USDT").cash());
        assertEquals(0, store.active("BTC-USDT").version());
        assertTrue(store.fills("BTC-USDT", a.sessionId(), null, 10).isEmpty());
        assertEquals(1, store.equity("BTC-USDT", a.sessionId(), null, 10).size());
    }

    @Test void newSessionArchivesHistoryAndFencesOldDecisionAndRiskState() {
        PaperAccount first = start("100");
        var buy = store.fill(command(first, "buy", "BUY", "10", "10", "0.01"));
        var oldRisk = store.risk("BTC-USDT", first.sessionId()).setEquityHighWatermark(new BigDecimal("10000"))
                .setConsecutiveLosses(8).setLossCooldownUntil(NOW.plusSeconds(999).toString());
        store.updateRisk("BTC-USDT", first.sessionId(), buy.account().version(), current -> oldRisk);
        assertThrows(ConcurrentModificationException.class, () -> store.start("BTC-USDT", "USDT", BigDecimal.TEN,
                first.sessionId(), 0L, NOW));
        PaperAccount second = store.start("BTC-USDT", "USDT", new BigDecimal("500"), first.sessionId(),
                buy.account().version(), NOW.plusSeconds(1));
        assertEquals("ARCHIVED", store.session("BTC-USDT", first.sessionId()).status());
        assertEquals(1, store.fills("BTC-USDT", first.sessionId(), null, 10).size());
        assertEquals(2, store.sessions("BTC-USDT", null, 10).size());
        decimal("500", second.cash()); decimal("0", second.positionQuantity());
        decimal("500", store.risk("BTC-USDT", second.sessionId()).getEquityHighWatermark());
        assertEquals(0, store.risk("BTC-USDT", second.sessionId()).getConsecutiveLosses());
        assertNull(store.risk("BTC-USDT", second.sessionId()).getLossCooldownUntil());
        assertThrows(ConcurrentModificationException.class, () -> store.fill(command(buy.account(), "old-decision", "BUY", "10", "10", "0.01")));
        assertThrows(ConcurrentModificationException.class, () -> store.updateRisk("BTC-USDT", first.sessionId(), buy.account().version(), current -> oldRisk));
        assertTrue(store.fill(command(first, "buy", "BUY", "10", "10", "0.01")).replay());
        decimal("500", store.active("BTC-USDT").cash());
    }

    @Test void marketSamplesTrackHoldAndConfigBoundariesAndNeverAcceptFutureOrRegressingTime() {
        PaperAccount a = start("100");
        store.mark("BTC-USDT", a.sessionId(), BigDecimal.TEN, NOW, "threshold", 1L, NOW);
        store.mark("BTC-USDT", a.sessionId(), new BigDecimal("11"), NOW.plusSeconds(1), "threshold", 1L, NOW.plusSeconds(1));
        assertEquals(2, store.equity("BTC-USDT", a.sessionId(), null, 100).size());
        store.mark("BTC-USDT", a.sessionId(), new BigDecimal("12"), NOW.plusSeconds(2), "threshold", 2L, NOW.plusSeconds(2));
        var points = store.equity("BTC-USDT", a.sessionId(), null, 100);
        assertEquals(3, points.size()); assertEquals(2L, points.getFirst().strategyConfigVersion());
        assertEquals(NOW.plusSeconds(2), points.getFirst().valuationAt());
        store.mark("BTC-USDT", a.sessionId(), new BigDecimal("13"), NOW.plusSeconds(63), null, null, NOW.plusSeconds(63));
        assertEquals(4, store.equity("BTC-USDT", a.sessionId(), null, 100).size());
        assertEquals(2L, store.equity("BTC-USDT", a.sessionId(), null, 100).getFirst().strategyConfigVersion());
        store.mark("BTC-USDT", a.sessionId(), new BigDecimal("100000"), NOW.plusSeconds(999), "threshold", 3L, NOW.plusSeconds(64));
        store.mark("BTC-USDT", a.sessionId(), BigDecimal.ONE, NOW, "threshold", 3L, NOW.plusSeconds(64));
        decimal("13", store.active("BTC-USDT").lastPrice());
        assertEquals(0, store.active("BTC-USDT").version());
    }

    @Test void staleDecisionCannotOverwriteRiskAfterAnotherFillOrRegressLatestValuation() {
        PaperAccount original = start("100");
        var staleRisk = store.risk("BTC-USDT", original.sessionId()).setConsecutiveOpenActions(0);
        var filled = store.fill(command(original, "newer-fill", "BUY", "10", "10", "0"));
        var newerRisk = store.risk("BTC-USDT", original.sessionId()).setConsecutiveOpenActions(1);
        store.updateRisk("BTC-USDT", original.sessionId(), filled.account().version(), current -> newerRisk);
        assertThrows(ConcurrentModificationException.class,
                () -> store.updateRisk("BTC-USDT", original.sessionId(), original.version(), current -> staleRisk));
        assertEquals(1, store.risk("BTC-USDT", original.sessionId()).getConsecutiveOpenActions());
        store.mark("BTC-USDT", original.sessionId(), new BigDecimal("12"), NOW.plusSeconds(1),
                "threshold", 1L, NOW.plusSeconds(1));
        assertThrows(ConcurrentModificationException.class,
                () -> store.fill(command(filled.account(), "old-price-fill", "BUY", "10", "10", "0")));
        decimal("12", store.active("BTC-USDT").lastPrice());
        assertEquals(NOW.plusSeconds(1), store.active("BTC-USDT").valuationAt());
        assertEquals(1, store.fills("BTC-USDT", original.sessionId(), null, 100).size());
        decimal("90", store.active("BTC-USDT").cash());
    }

    @Test void sameAccountRevisionRiskTransitionsAlwaysReadLatestLockedRisk() throws Exception {
        PaperAccount account = start("100");
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> increment = () -> {
                gate.await();
                store.updateRisk("BTC-USDT", account.sessionId(), account.version(), current ->
                        current.setConsecutiveOpenActions(current.getConsecutiveOpenActions() + 1));
                return null;
            };
            var first = pool.submit(increment); var second = pool.submit(increment);
            gate.countDown(); first.get(); second.get();
        }
        assertEquals(2, store.risk("BTC-USDT", account.sessionId()).getConsecutiveOpenActions());
        assertEquals(0, store.active("BTC-USDT").version());
    }

    @Test void concurrentSpendAndResetCannotOverwriteEachOther() throws Exception {
        PaperAccount a = start("100");
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> spend = () -> { gate.await(); try {
                store.fill(command(a, Thread.currentThread().getName(), "BUY", "80", "10", "0")); return true;
            } catch (ConcurrentModificationException ignored) { return false; } };
            var one = pool.submit(spend); var two = pool.submit(spend); gate.countDown();
            assertNotEquals(one.get(), two.get());
        }
        decimal("20", store.active("BTC-USDT").cash());
        assertEquals(1, store.fills("BTC-USDT", a.sessionId(), null, 100).size());
        PaperAccount current = store.active("BTC-USDT");
        CountDownLatch resetGate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reset = pool.submit(() -> { resetGate.await(); try {
                store.start("BTC-USDT", "USDT", new BigDecimal("300"), current.sessionId(), current.version(), NOW); return true;
            } catch (ConcurrentModificationException ignored) { return false; } });
            var fill = pool.submit(() -> { resetGate.await(); try {
                store.fill(command(current, "race-reset", "BUY", "10", "10", "0")); return true;
            } catch (ConcurrentModificationException ignored) { return false; } });
            resetGate.countDown(); assertNotEquals(reset.get(), fill.get());
        }
        assertTrue(store.active("BTC-USDT").cash().signum() >= 0);
    }

    @Test void effectiveContextAndBrokerUseOnlySimulationCashAndPositionAndVersionedFills() {
        TradingProperties props = new TradingProperties(); props.getRisk().setEnabled(false);
        props.setMaxBuyQuoteAmount(new BigDecimal("1000"));
        PaperAccountService service = new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC));
        TradingDecisionContext context = context();
        service.enrichContext(context);
        decimal("0", TradingMarketInputs.availableBalance(context.getQuoteBalance()));
        decimal("0", context.getTradingState().getTrackedBaseAmount());
        assertNull(context.getPaperSessionId());
        service.start(new BigDecimal("50"), null, null); service.enrichContext(context);
        decimal("50", TradingMarketInputs.estimatedEquity(context));
        TradingStateStore legacy = mock(TradingStateStore.class);
        RiskControlService risk = new RiskControlService(props, legacy, Clock.fixed(NOW, ZoneOffset.UTC),
                new TradingRiskConfiguration().tradingRiskRules(), service);
        PaperOrderExecutionService executor = new PaperOrderExecutionService(new OrderSizingService(props), props, risk, service);
        TradingDecisionRecord record = new TradingDecisionRecord().setDecisionId("real-paper-buy").setStrategyId("threshold")
                .setStrategyConfigVersion(4).setStrategyConfigSnapshot(Map.of("params", Map.of("lookback", 10)));
        service.markDecision(context, record);
        executor.execute(new StrategyDecision().setAction(TradingAction.BUY).setBuyQuoteAmount(new BigDecimal("20")), context, record);
        assertEquals("PAPER_FILLED", record.getExecutionStatus());
        decimal("30", service.current().cash());
        assertEquals(4, service.fills(context.getPaperSessionId(), null, 10).getFirst().strategyConfigVersion());
        verifyNoInteractions(legacy);
    }

    @Test void confirmedSignalsReplayAcrossConcurrentServiceInstancesAndSizeChangesButNotNewTrials() throws Exception {
        TradingProperties props = new TradingProperties();
        PaperAccount original = start("100");
        PaperAccountService firstService = new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC));
        PaperAccountService secondService = new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC));
        TradingDecisionContext firstContext = firstService.enrichContext(context());
        TradingDecisionContext secondContext = secondService.enrichContext(context());
        TradingDecisionRecord firstRecord = signalRecord("random-one", 1L);
        TradingDecisionRecord secondRecord = signalRecord("random-two", 2L);
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return firstService.fill(firstContext, firstRecord, "BUY", BigDecimal.TEN); });
            var second = pool.submit(() -> { gate.await(); return secondService.fill(secondContext, secondRecord, "BUY", BigDecimal.TEN); });
            gate.countDown();
            var firstFill = first.get(); var secondFill = second.get();
            assertNotEquals(firstFill.replay(), secondFill.replay());
            assertEquals(firstFill.fill().decisionId(), secondFill.fill().decisionId());
        }
        assertEquals(firstRecord.getDecisionId(), secondRecord.getDecisionId());
        assertEquals(64, firstRecord.getDecisionId().length());
        assertEquals(1, store.fills("BTC-USDT", original.sessionId(), null, 100).size());
        assertEquals(1, store.active("BTC-USDT").version());
        decimal("90", store.active("BTC-USDT").cash());

        // A fresh service and changed parameters cannot spend the same signal again.
        PaperAccountService restarted = new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC));
        TradingDecisionRecord resized = signalRecord("random-three", 3L);
        var replay = restarted.fill(restarted.enrichContext(context()), resized, "BUY", new BigDecimal("20"));
        assertTrue(replay.replay());
        assertEquals(firstRecord.getDecisionId(), resized.getDecisionId());
        decimal("10", replay.fill().requestedSize());
        decimal("90", store.active("BTC-USDT").cash());

        // A replacement PAPER trial is an independent experiment with its own ledger identity.
        PaperAccount current = store.active("BTC-USDT");
        PaperAccount replacement = store.start("BTC-USDT", "USDT", new BigDecimal("100"),
                current.sessionId(), current.version(), NOW);
        TradingDecisionRecord newTrial = signalRecord("random-four", 3L);
        var independent = restarted.fill(restarted.enrichContext(context()), newTrial, "BUY", BigDecimal.TEN);
        assertFalse(independent.replay());
        assertNotEquals(firstRecord.getDecisionId(), newTrial.getDecisionId());
        assertEquals(1, store.fills("BTC-USDT", replacement.sessionId(), null, 100).size());
        assertEquals(1, store.fills("BTC-USDT", original.sessionId(), null, 100).size());
    }

    private static TradingDecisionRecord signalRecord(String decisionId, long configVersion) {
        return new TradingDecisionRecord().setDecisionId(decisionId).setStrategyId("intraday-15m")
                .setStrategyConfigVersion(configVersion).setStrategyConfigSnapshot(Map.of("buy-quote-amount", configVersion * 10))
                .setMetadata(Map.of("signalBar", "15m", "signalCandleTs", NOW.minusSeconds(900).toEpochMilli()));
    }

    private PaperAccount start(String cash) { return store.start("BTC-USDT", "USDT", new BigDecimal(cash), null, null, NOW); }
    private PaperAccountStore.FillCommand command(PaperAccount a, String decisionId, String side, String size, String price, String fee) {
        return new PaperAccountStore.FillCommand("BTC-USDT", a.sessionId(), a.version(), decisionId, "threshold", 1,
                "{\"params\":{\"lookback\":5}}", side, new BigDecimal(size), new BigDecimal(price), new BigDecimal(fee), NOW, NOW);
    }
    private TradingDecisionContext context() {
        TickerResp ticker = new TickerResp(); ticker.setLast("10"); ticker.setTs(Long.toString(NOW.toEpochMilli()));
        BalanceDetail exchange = new BalanceDetail(); exchange.setAvailBal("999999");
        AccountBalanceResp account = new AccountBalanceResp(); account.setTotalEq("9999999");
        InstrumentInfoResp rules = new InstrumentInfoResp(); rules.setMinSz("0.00001"); rules.setLotSz("0.00001");
        rules.setMaxMktAmt("1000"); rules.setMaxMktSz("10000");
        return new TradingDecisionContext().setTicker(ticker).setBaseBalance(exchange).setQuoteBalance(exchange)
                .setAccountBalance(account).setInstrument(rules).setTradingState(new TradingState()
                        .setTrackedBaseAmount(new BigDecimal("999")).setAverageCost(BigDecimal.ONE));
    }
    private static void decimal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toPlainString()); }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { JdbcDataSource d = new JdbcDataSource(); d.setURL("jdbc:h2:mem:paper_account;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"); return d; }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource d) throws Exception {
            SqlSessionFactoryBean f = new SqlSessionFactoryBean(); f.setDataSource(d);
            var c = new org.apache.ibatis.session.Configuration(); c.setMapUnderscoreToCamelCase(true); f.setConfiguration(c);
            f.setMapperLocations(new ClassPathResource("mapper/trading/PaperAccountMapper.xml")); return f.getObject();
        }
        @Bean MapperFactoryBean<PaperAccountMapper> mapper(SqlSessionFactory f) { var m = new MapperFactoryBean<>(PaperAccountMapper.class); m.setSqlSessionFactory(f); return m; }
        @Bean PaperAccountStore store(PaperAccountMapper mapper) { return new MyBatisPaperAccountStore(mapper); }
        @Bean JdbcTemplate jdbc(DataSource d) { return new JdbcTemplate(d); }
        @Bean PlatformTransactionManager transactionManager(DataSource d) { return new DataSourceTransactionManager(d); }
    }
}
