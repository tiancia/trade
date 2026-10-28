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
import com.trade.trading.port.PaperAccountStore;
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.domain.risk.RiskAssessment;
import com.trade.trading.domain.risk.RiskStateTransitions;
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
import java.util.NoSuchElementException;
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

    @Test void dailyStatisticsCountCompleteRoundTripsAcrossMidnightAndFenceSessionVersion() {
        Instant midnight = Instant.parse("2026-10-19T16:00:00Z"); // Asia/Shanghai next-day boundary.
        PaperAccount initial = store.start("BTC-USDT", "USDT", new BigDecimal("1000"), null, null, midnight.minusSeconds(60));
        var buy = store.fill(timedCommand(initial, "overnight-buy", "BUY", "100", midnight.minusSeconds(30)));
        var partial = store.fill(timedCommand(buy.account(), "partial-sell", "SELL", "4.9", midnight));
        var closeCommand = timedCommand(partial.account(), "close-sell", "SELL", "5", midnight.plusSeconds(30));
        var close = store.fill(closeCommand);
        assertTrue(store.fill(closeCommand).replay());
        // A next-day trade and another simulation must not enter this day's totals.
        var nextDay = store.fill(timedCommand(close.account(), "next-day-buy", "BUY", "10", midnight.plusSeconds(86400)));
        var metrics = store.dailyPerformance("BTC-USDT", initial.sessionId(), nextDay.account().version(), midnight, midnight.plusSeconds(86400));
        assertEquals(2, metrics.fillCount());
        assertEquals(0, metrics.buyCount());
        assertEquals(2, metrics.sellCount());
        assertEquals(1, metrics.closedTrades());
        decimal("17.612", metrics.realizedNetPnl()); // Includes allocated entry fees; do not deduct them again.
        decimal("1.188", metrics.feesQuote()); // Only fees actually incurred on this day.
        var beforeClose = store.dailyPerformance("BTC-USDT", initial.sessionId(), partial.account().version(), midnight, midnight.plusSeconds(86400));
        assertEquals(1, beforeClose.fillCount());
        assertEquals(0, beforeClose.closedTrades());
        var yesterday = store.dailyPerformance("BTC-USDT", initial.sessionId(), nextDay.account().version(), midnight.minusSeconds(86400), midnight);
        assertEquals(1, yesterday.buyCount());
        assertEquals(0, yesterday.closedTrades());
        decimal("0", yesterday.realizedNetPnl());
        decimal("1", yesterday.feesQuote());
        var replacement = store.start("BTC-USDT", "USDT", BigDecimal.TEN, initial.sessionId(), nextDay.account().version(), midnight.plusSeconds(86401));
        assertEquals(0, store.dailyPerformance("BTC-USDT", replacement.sessionId(), replacement.version(), midnight, midnight.plusSeconds(86400)).fillCount());
        assertEquals(0, store.dailyPerformance("ETH-USDT", initial.sessionId(), nextDay.account().version(), midnight, midnight.plusSeconds(86400)).fillCount());
    }

    private PaperAccountStore.FillCommand timedCommand(PaperAccount account, String id, String side, String size, Instant time) {
        return new PaperAccountStore.FillCommand("BTC-USDT", account.sessionId(), account.version(), id, "intraday", 1,
                "{}", side, new BigDecimal(size), new BigDecimal("BUY".equals(side) ? "10" : "12"), new BigDecimal("0.01"), time, time);
    }

    @Test void deletionRemovesOnlyTheSelectedTrialAndItsLedger() {
        PaperAccount first = start("100");
        var filled = store.fill(command(first, "old-buy", "BUY", "10", "10", "0"));
        PaperAccount current = store.start("BTC-USDT", "USDT", new BigDecimal("200"), first.sessionId(), filled.account().version(), NOW);
        assertThrows(NoSuchElementException.class, () -> store.delete("ETH-USDT", first.sessionId(), filled.account().version()));
        assertThrows(ConcurrentModificationException.class, () -> store.delete("BTC-USDT", first.sessionId(), first.version()));
        assertEquals(1, store.fills("BTC-USDT", first.sessionId(), null, 100).size());
        store.delete("BTC-USDT", first.sessionId(), filled.account().version());
        assertNull(store.session("BTC-USDT", first.sessionId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_paper_fills WHERE session_id=?", Integer.class, first.sessionId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_paper_equity WHERE session_id=?", Integer.class, first.sessionId()));
        assertEquals(current.sessionId(), store.active("BTC-USDT").sessionId());
        assertEquals(1, store.equity("BTC-USDT", current.sessionId(), null, 100).size());
        store.delete("BTC-USDT", current.sessionId(), current.version());
        assertNull(store.active("BTC-USDT"));
        assertTrue(store.sessions("BTC-USDT", null, 100).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.fill(command(current, "late-buy", "BUY", "10", "10", "0")));
        PaperAccount next = start("300");
        assertNotEquals(current.sessionId(), next.sessionId());
        assertThrows(ConcurrentModificationException.class, () -> store.fill(command(current, "late-buy", "BUY", "10", "10", "0")));
        decimal("300", store.active("BTC-USDT").cash());
    }

    @Test void deletionAndConcurrentFillCannotBothCommit() throws Exception {
        PaperAccount account = start("100");
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> { gate.await(); try {
                store.delete("BTC-USDT", account.sessionId(), account.version()); return true;
            } catch (ConcurrentModificationException conflict) { return false; } });
            var fill = pool.submit(() -> { gate.await(); try {
                store.fill(command(account, "racing-buy", "BUY", "10", "10", "0")); return true;
            } catch (IllegalStateException conflict) { return false; } });
            gate.countDown();
            assertNotEquals(deletion.get(), fill.get());
        }
        if (store.active("BTC-USDT") == null) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_paper_fills", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_paper_equity", Integer.class));
        } else {
            decimal("90", store.active("BTC-USDT").cash());
            assertEquals(1, store.fills("BTC-USDT", account.sessionId(), null, 100).size());
        }
    }

    @Test void failedDeletionRollsBackHeadAndChildRecords() {
        PaperAccount account = store.fill(command(start("100"), "buy", "BUY", "10", "10", "0")).account();
        jdbc.execute("CREATE TABLE paper_delete_blocker(session_id VARCHAR(64) REFERENCES okx_paper_sessions(session_id))");
        try {
            jdbc.update("INSERT INTO paper_delete_blocker VALUES(?)", account.sessionId());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> store.delete("BTC-USDT", account.sessionId(), account.version()));
            assertEquals(account.sessionId(), store.active("BTC-USDT").sessionId());
            assertEquals(1, store.fills("BTC-USDT", account.sessionId(), null, 100).size());
            assertEquals(2, store.equity("BTC-USDT", account.sessionId(), null, 100).size());
        } finally { jdbc.execute("DROP TABLE paper_delete_blocker"); }
    }

    @Test void serviceRejectsDeletionOutsidePaperOrWithoutAValidRevision() {
        PaperAccount account = start("100");
        TradingProperties properties = new TradingProperties();
        PaperAccountService service = new PaperAccountService(store, properties);
        assertThrows(IllegalArgumentException.class, () -> service.delete(account.sessionId(), null));
        assertThrows(IllegalArgumentException.class, () -> service.delete(account.sessionId(), -1L));
        properties.setExecutionMode(ExecutionMode.LIVE);
        assertThrows(IllegalStateException.class, () -> service.delete(account.sessionId(), account.version()));
        assertNotNull(store.active("BTC-USDT"));
        properties.setExecutionMode(ExecutionMode.PAPER);
        service.delete(account.sessionId(), account.version());
        assertNull(service.current());
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
        assertNull(store.risk("BTC-USDT", a.sessionId()).getLastTradeTime());
    }

    @Test void tradeTimeCommitsWithFillAndSurvivesReplaysLateFillsAndStaleRiskTransitions() {
        PaperAccount original = start("100");
        var buy = store.fill(commandAt(original, "first", NOW.plusSeconds(10)));
        assertEquals(NOW.plusSeconds(10).toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
        assertEquals(0, store.risk("BTC-USDT", original.sessionId()).getConsecutiveOpenActions());

        assertTrue(store.fill(commandAt(original, "first", NOW.plusSeconds(60))).replay());
        assertEquals(NOW.plusSeconds(10).toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
        var late = store.fill(commandAt(buy.account(), "late", NOW.plusSeconds(5)));
        assertEquals(NOW.plusSeconds(10).toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
        var newer = store.fill(commandAt(late.account(), "newer", NOW.plusSeconds(20)));
        assertEquals(NOW.plusSeconds(20).toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());

        // Both in-place and replacement secondary transitions must retain the committed fact.
        store.updateRisk("BTC-USDT", original.sessionId(), newer.account().version(),
                current -> current.setLastTradeTime(NOW.toString()).setConsecutiveOpenActions(2));
        store.updateRisk("BTC-USDT", original.sessionId(), newer.account().version(),
                current -> RiskStateTransitions.copyRiskState(current).setLastTradeTime(null));
        assertEquals(NOW.plusSeconds(20).toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
        assertEquals(2, store.risk("BTC-USDT", original.sessionId()).getConsecutiveOpenActions());
    }

    @Test void failureAfterTradeTimeWriteRollsBackCashPositionFillAndTimeTogether() {
        PaperAccount original = start("100");
        jdbc.execute("ALTER TABLE okx_paper_equity ADD CONSTRAINT reject_fill_equity CHECK (kind <> 'FILL')");
        assertThrows(RuntimeException.class,
                () -> store.fill(command(original, "retry-after-rollback", "BUY", "10", "10", "0")));
        decimal("100", store.active("BTC-USDT").cash());
        decimal("0", store.active("BTC-USDT").positionQuantity());
        assertEquals(0, store.active("BTC-USDT").version());
        assertNull(store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
        assertTrue(store.fills("BTC-USDT", original.sessionId(), null, 10).isEmpty());
        assertEquals(1, store.equity("BTC-USDT", original.sessionId(), null, 10).size());

        jdbc.execute("ALTER TABLE okx_paper_equity DROP CONSTRAINT reject_fill_equity");
        assertFalse(store.fill(command(original, "retry-after-rollback", "BUY", "10", "10", "0")).replay());
        assertEquals(NOW.toString(), store.risk("BTC-USDT", original.sessionId()).getLastTradeTime());
    }

    @Test void secondaryRiskFailureCannotEraseTradeTimeRecoveredByAFreshService() {
        TradingProperties props = new TradingProperties();
        PaperAccount original = start("100");
        PaperAccountService service = new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC));
        TradingDecisionContext context = service.enrichContext(context());
        RiskControlService risk = mock(RiskControlService.class);
        when(risk.evaluate(any(), eq(context))).thenReturn(new RiskAssessment().setAllowed(true));
        doThrow(new IllegalStateException("secondary risk write unavailable")).when(risk).recordExecutedAction(any(), eq(context));
        PaperOrderExecutionService executor = new PaperOrderExecutionService(new OrderSizingService(props), props, risk, service);
        var record = new TradingDecisionRecord().setDecisionId("committed-before-risk-error").setStrategyId("threshold")
                .setStrategyConfigVersion(1).setStrategyConfigSnapshot(Map.of());
        executor.execute(StrategyDecision.buy("threshold", BigDecimal.TEN, "test"), context, record);
        assertEquals("PAPER_FILLED", record.getExecutionStatus());
        assertTrue(record.getMetadata().containsKey("paperRiskUpdateWarning"));

        PaperAccountService restarted = new PaperAccountService(store, props, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        assertEquals(NOW.toString(), restarted.enrichContext(context()).getTradingState().getRiskState().getLastTradeTime());
        assertEquals(0, store.risk("BTC-USDT", original.sessionId()).getConsecutiveOpenActions());
        assertEquals(1, store.fills("BTC-USDT", original.sessionId(), null, 10).size());
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

    @Test void strategyMaintenanceFencesOldDecisionsAndRequiresSameFlatSession() {
        PaperAccount original = start("100");
        assertTrue(store.changeStrategyWhileFlat("BTC-USDT", original.sessionId(), original.version(), () -> true));
        assertEquals(1, store.active("BTC-USDT").version());
        assertThrows(ConcurrentModificationException.class,
                () -> store.fill(command(original, "old-strategy", "BUY", "20", "10", "0.001")));
        assertFalse(store.changeStrategyWhileFlat("BTC-USDT", original.sessionId(), 1, () -> false));
        assertEquals(1, store.active("BTC-USDT").version());
        var bought = store.fill(command(store.active("BTC-USDT"), "new-strategy", "BUY", "20", "10", "0.001"));
        assertThrows(ConcurrentModificationException.class,
                () -> store.changeStrategyWhileFlat("BTC-USDT", original.sessionId(), bought.account().version(), () -> fail("Cannot change strategy with a position")));
    }

    @Test void concurrentStrategyMaintenanceAndSpendHaveOnlyOneWinner() throws Exception {
        PaperAccount original = start("100");
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change = pool.submit(() -> { gate.await(); try {
                return store.changeStrategyWhileFlat("BTC-USDT", original.sessionId(), 0, () -> true);
            } catch (ConcurrentModificationException ignored) { return false; } });
            var spend = pool.submit(() -> { gate.await(); try {
                store.fill(command(original, "race-ai", "BUY", "20", "10", "0.001")); return true;
            } catch (ConcurrentModificationException ignored) { return false; } });
            gate.countDown();
            assertNotEquals(change.get(5, java.util.concurrent.TimeUnit.SECONDS), spend.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, store.active("BTC-USDT").version());
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
        assertEquals(NOW.toString(), store.risk("BTC-USDT", context.getPaperSessionId()).getLastTradeTime());
        assertEquals(0, store.risk("BTC-USDT", context.getPaperSessionId()).getConsecutiveOpenActions());
        assertEquals(NOW.toString(), new PaperAccountService(store, props, Clock.fixed(NOW, ZoneOffset.UTC))
                .enrichContext(context()).getTradingState().getRiskState().getLastTradeTime());
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
    private PaperAccountStore.FillCommand commandAt(PaperAccount account, String decisionId, Instant fillTime) {
        return new PaperAccountStore.FillCommand("BTC-USDT", account.sessionId(), account.version(), decisionId,
                "threshold", 1, "{}", "BUY", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, NOW, fillTime);
    }
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
