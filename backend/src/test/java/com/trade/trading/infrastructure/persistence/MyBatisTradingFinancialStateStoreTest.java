package com.trade.trading.infrastructure.persistence;

import com.trade.trading.application.port.FundSafetyRepository;
import com.trade.trading.application.port.TradingFinancialStateStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingPositionState;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.order.SpotFillApplication;
import com.trade.trading.domain.risk.FundSafetyStatus;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.config.TradingRiskConfiguration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(MyBatisTradingFinancialStateStoreTest.PersistenceTestConfiguration.class)
@Sql(statements = {
        "DROP TABLE IF EXISTS okx_order_fill_ledger",
        "DROP TABLE IF EXISTS okx_risk_state",
        "DROP TABLE IF EXISTS okx_position_state",
        "DROP TABLE IF EXISTS okx_fund_safety_state",
        "DROP TABLE IF EXISTS okx_orders",
        "CREATE TABLE okx_orders (id BIGINT AUTO_INCREMENT PRIMARY KEY)",
        "CREATE TABLE okx_position_state (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "account_scope VARCHAR(32) NOT NULL, inst_id VARCHAR(64) NOT NULL," +
                "position_side VARCHAR(16) NOT NULL, quantity DECIMAL(38,18) NOT NULL," +
                "average_cost DECIMAL(38,18) NOT NULL, exchange_quantity DECIMAL(38,18)," +
                "last_reconciled_at TIMESTAMP, version BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL," +
                "UNIQUE(account_scope, inst_id))",
        "CREATE TABLE okx_risk_state (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "account_scope VARCHAR(32) NOT NULL UNIQUE, current_equity DECIMAL(38,18) NOT NULL," +
                "equity_high_watermark DECIMAL(38,18) NOT NULL, day_start_equity DECIMAL(38,18) NOT NULL," +
                "day_start_date VARCHAR(16), consecutive_losses INT NOT NULL, loss_cooldown_until TIMESTAMP," +
                "last_trade_time TIMESTAMP, consecutive_open_actions INT NOT NULL, last_risk_reason VARCHAR(1000)," +
                "consecutive_reconciliation_failures INT NOT NULL, last_reconciliation_at TIMESTAMP," +
                "last_reconciliation_error VARCHAR(1000), version BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
        "CREATE TABLE okx_fund_safety_state (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "account_scope VARCHAR(32) NOT NULL UNIQUE, status VARCHAR(16) NOT NULL," +
                "reason VARCHAR(1000), source VARCHAR(64), resume_reason VARCHAR(1000)," +
                "last_action_error VARCHAR(1000), halted_at TIMESTAMP, resumed_at TIMESTAMP," +
                "updated_at TIMESTAMP NOT NULL, version BIGINT NOT NULL)",
        "CREATE TABLE okx_order_fill_ledger (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "order_id BIGINT NOT NULL UNIQUE, side VARCHAR(16) NOT NULL," +
                "cumulative_filled_size DECIMAL(38,18) NOT NULL," +
                "applied_position_quantity DECIMAL(38,18) NOT NULL," +
                "applied_quote_cost DECIMAL(38,18) NOT NULL," +
                "average_fill_price DECIMAL(38,18), fee DECIMAL(38,18), fee_ccy VARCHAR(32)," +
                "exchange_state VARCHAR(32), exchange_updated_at TIMESTAMP, version BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL," +
                "FOREIGN KEY(order_id) REFERENCES okx_orders(id))",
        "INSERT INTO okx_orders(id) VALUES (1)"
})
class MyBatisTradingFinancialStateStoreTest {
    @Autowired
    private TradingFinancialStateStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FundSafetyRepository fundSafetyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path tempDir;

    @Test
    void positionCostRiskAndCumulativeFillAreDurableAndIdempotent() {
        store.getOrCreatePosition("paper", "BTC-USDT", new BigDecimal("0.1"), new BigDecimal("50000"));
        Long paperPositionId = jdbcTemplate.queryForObject(
                "SELECT id FROM okx_position_state WHERE account_scope = 'paper' AND inst_id = 'BTC-USDT'",
                Long.class);
        assertTrue(paperPositionId > 0);
        TradingPositionState paper = store.recordBuy(
                "paper",
                "BTC-USDT",
                new BigDecimal("0.1"),
                new BigDecimal("60000")
        );
        assertDecimal("0.2", paper.getQuantity());
        assertDecimal("55000", paper.getAverageCost());
        TradingPositionState reconciledPaper = store.recordExchangePosition(
                "paper",
                "BTC-USDT",
                new BigDecimal("0.2"),
                new BigDecimal("0.2"),
                null,
                Instant.now()
        );
        assertDecimal("55000", reconciledPaper.getAverageCost());

        TradingRiskState savedRisk = store.saveRiskState("paper", new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1200"))
                .setConsecutiveReconciliationFailures(2)
                .setLastReconciliationAt("2026-07-26T10:00:00Z"));
        assertDecimal("1000", savedRisk.getCurrentEquity());
        assertEquals(2, store.getOrCreateRiskState("paper", null).getConsecutiveReconciliationFailures());
        store.recordReconciliationFailure("paper", Instant.now(), "first failure");
        store.recordReconciliationFailure("paper", Instant.now(), "second failure");
        store.saveRiskState("paper", new TradingRiskState().setCurrentEquity(new BigDecimal("900")));
        assertEquals(4, store.getOrCreateRiskState("paper", null).getConsecutiveReconciliationFailures());
        assertEquals(
                0,
                store.recordReconciliationSuccess("paper", Instant.now())
                        .getConsecutiveReconciliationFailures()
        );

        SpotFillApplication first = store.applyCumulativeSpotFill(
                1L,
                "live",
                "BTC-USDT",
                "buy",
                new BigDecimal("0.002"),
                new BigDecimal("0.001998"),
                new BigDecimal("100"),
                new BigDecimal("50000"),
                new BigDecimal("-0.000002"),
                "BTC",
                "partially_filled",
                Instant.now()
        );
        SpotFillApplication replay = store.applyCumulativeSpotFill(
                1L,
                "live",
                "BTC-USDT",
                "buy",
                new BigDecimal("0.002"),
                new BigDecimal("0.001998"),
                new BigDecimal("100"),
                new BigDecimal("50000"),
                new BigDecimal("-0.000002"),
                "BTC",
                "partially_filled",
                Instant.now()
        );

        assertTrue(first.changed());
        assertTrue(first.firstApplication());
        assertFalse(replay.changed());
        TradingPositionState live = store.getOrCreatePosition(
                "live",
                "BTC-USDT",
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        assertDecimal("0.001998", live.getQuantity());
        assertDecimal(
                new BigDecimal("100").divide(new BigDecimal("0.001998"), 18, java.math.RoundingMode.HALF_UP)
                        .toPlainString(),
                live.getAverageCost()
        );
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okx_order_fill_ledger",
                Integer.class
        ));
        assertEquals(paperPositionId, jdbcTemplate.queryForObject(
                "SELECT id FROM okx_position_state WHERE account_scope = 'paper' AND inst_id = 'BTC-USDT'",
                Long.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT id FROM okx_position_state WHERE account_scope = 'live' AND inst_id = 'BTC-USDT'",
                Long.class) > paperPositionId);
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT id FROM okx_risk_state WHERE account_scope = 'paper'", Long.class) > 0);
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_risk_state", Integer.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT id FROM okx_order_fill_ledger WHERE order_id = 1", Long.class) > 0);
    }

    @Test
    void fundStopSurvivesReadsAndRequiresExpectedRevisionToResume() {
        var bootstrap = fundSafetyRepository.getOrCreate("live");
        Long fundSafetyId = jdbcTemplate.queryForObject(
                "SELECT id FROM okx_fund_safety_state WHERE account_scope = 'live'", Long.class);
        assertTrue(fundSafetyId > 0);
        assertEquals(FundSafetyStatus.HALTED, bootstrap.getStatus());
        assertEquals("bootstrap", bootstrap.getSource());
        var active = fundSafetyRepository.resume(
                "live",
                bootstrap.getVersion(),
                "initial reconciliation complete",
                Instant.now()
        );
        assertEquals(FundSafetyStatus.ACTIVE, active.getStatus());
        var halted = fundSafetyRepository.halt("live", "test", "risk limit", Instant.now());
        assertEquals(FundSafetyStatus.HALTED, halted.getStatus());
        assertEquals(2L, halted.getVersion());
        assertEquals(FundSafetyStatus.HALTED, fundSafetyRepository.getOrCreate("live").getStatus());
        assertThrows(ConcurrentModificationException.class, () -> fundSafetyRepository.resume(
                "live", bootstrap.getVersion(), "stale operator view", Instant.now()));
        assertEquals(FundSafetyStatus.HALTED, fundSafetyRepository.getOrCreate("live").getStatus());

        var resumed = fundSafetyRepository.resume("live", halted.getVersion(), "operator checked", Instant.now());
        assertEquals(FundSafetyStatus.ACTIVE, resumed.getStatus());
        assertEquals(3L, resumed.getVersion());
        assertEquals(fundSafetyId, jdbcTemplate.queryForObject(
                "SELECT id FROM okx_fund_safety_state WHERE account_scope = 'live'", Long.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_fund_safety_state", Integer.class));
    }

    @Test
    void rejectedSellRollsBackLedgerAndLeavesManagedPositionUntouched() {
        store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ONE, BigDecimal.TEN);
        assertThrows(IllegalStateException.class, () -> store.applyCumulativeSpotFill(
                1L, "live", "BTC-USDT", "sell", new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO,
                BigDecimal.TEN, BigDecimal.ZERO, "USDT", "filled", Instant.now()));
        var position = store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO, BigDecimal.ZERO);
        assertDecimal("1", position.getQuantity());
        assertEquals(0, position.getVersion());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_order_fill_ledger", Integer.class));
    }

    @Test
    void inconsistentFinalFillFailsInsteadOfClosingWithAnOlderCapitalCheckpoint() {
        Instant firstTime = Instant.parse("2026-10-23T00:00:00Z");
        store.applyCumulativeSpotFill(1L, "live", "BTC-USDT", "buy",
                new BigDecimal("2"), new BigDecimal("1.98"), new BigDecimal("200"),
                new BigDecimal("100"), new BigDecimal("-0.02"), "BTC", "partially_filled", firstTime);

        assertThrows(IllegalStateException.class, () -> store.applyCumulativeSpotFill(
                1L, "live", "BTC-USDT", "buy", new BigDecimal("3"), new BigDecimal("2.97"),
                new BigDecimal("190"), new BigDecimal("63.333333333333333333"),
                new BigDecimal("-0.03"), "BTC", "filled", firstTime.plusSeconds(1)));

        assertDecimal("1.98", store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO, BigDecimal.ZERO).getQuantity());
        assertDecimal("2", jdbcTemplate.queryForObject(
                "SELECT cumulative_filled_size FROM okx_order_fill_ledger WHERE order_id = 1", BigDecimal.class));
        assertEquals("partially_filled", jdbcTemplate.queryForObject(
                "SELECT exchange_state FROM okx_order_fill_ledger WHERE order_id = 1", String.class));
        assertEquals(firstTime.toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
    }

    @Test
    void positiveFillWithNonpositiveNetQuantityCannotBeSilentlyDiscarded() {
        assertThrows(IllegalStateException.class, () -> store.applyCumulativeSpotFill(
                1L, "live", "BTC-USDT", "buy", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ONE.negate(), "BTC", "filled", Instant.now()));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_order_fill_ledger", Integer.class));
    }

    @Test
    void advancingFillsCommitTradeTimeWithoutRiskCallbacksAndStaleRiskWritesCannotRegressIt() {
        Instant firstTime = Instant.parse("2026-10-23T00:00:00Z");
        TradingRiskState staleRisk = store.getOrCreateRiskState("live", new TradingRiskState()
                .setLastTradeTime(firstTime.minusSeconds(60).toString()).setConsecutiveOpenActions(2)
                .setConsecutiveLosses(1).setLastRiskReason("preserved")
                .setConsecutiveReconciliationFailures(3).setCurrentEquity(new BigDecimal("1000")));
        assertTrue(applyBuyFill(store, "1", firstTime).firstApplication());
        assertEquals(firstTime.toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
        long firstVersion = riskVersion();

        // A repeated observation is not a new fill, even with a newer exchange timestamp.
        assertFalse(applyBuyFill(store, "1", firstTime.plusSeconds(120)).changed());
        assertEquals(firstTime.toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
        assertEquals(firstVersion, riskVersion());

        Instant secondTime = firstTime.plusSeconds(30);
        SpotFillApplication partial = applyBuyFill(store, "2", secondTime);
        assertTrue(partial.changed());
        assertFalse(partial.firstApplication());
        assertEquals(secondTime.toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
        assertEquals(firstVersion + 1, riskVersion());

        assertTrue(applyBuyFill(store, "3", firstTime.plusSeconds(10)).changed());
        TradingRiskState committed = store.getOrCreateRiskState("live", null);
        assertEquals(secondTime.toString(), committed.getLastTradeTime());
        assertEquals(firstVersion + 2, riskVersion());
        assertEquals(2, committed.getConsecutiveOpenActions());
        assertEquals(1, committed.getConsecutiveLosses());
        assertEquals(3, committed.getConsecutiveReconciliationFailures());
        assertEquals("preserved", committed.getLastRiskReason());
        assertDecimal("1000", committed.getCurrentEquity());

        assertEquals(secondTime.toString(), store.saveRiskState("live", staleRisk).getLastTradeTime());
        assertEquals(secondTime.toString(), store.saveRiskState("live",
                staleRisk.setLastTradeTime(null)).getLastTradeTime());
        assertDecimal("3", store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO,
                BigDecimal.ZERO).getQuantity());
    }

    @Test
    void failureAfterTradeTimeWriteRollsBackPositionTimeAndFillCheckpointTogether() {
        Instant previousTime = Instant.parse("2026-10-22T23:00:00Z");
        store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ONE, BigDecimal.TEN);
        store.getOrCreateRiskState("live", new TradingRiskState().setLastTradeTime(previousTime.toString())
                .setConsecutiveOpenActions(2));
        jdbcTemplate.execute("ALTER TABLE okx_order_fill_ledger ADD CONSTRAINT reject_filled "
                + "CHECK (exchange_state IS NULL OR exchange_state <> 'filled')");
        assertThrows(RuntimeException.class, () -> applyBuyFill(store, "1", previousTime.plusSeconds(60)));
        assertDecimal("1", store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO,
                BigDecimal.ZERO).getQuantity());
        assertEquals(previousTime.toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
        assertEquals(0, riskVersion());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_order_fill_ledger", Integer.class));

        jdbcTemplate.execute("ALTER TABLE okx_order_fill_ledger DROP CONSTRAINT reject_filled");
        assertTrue(applyBuyFill(store, "1", previousTime.plusSeconds(60)).changed());
        assertEquals(previousTime.plusSeconds(60).toString(), store.getOrCreateRiskState("live", null).getLastTradeTime());
        assertDecimal("2", store.getOrCreatePosition("live", "BTC-USDT", BigDecimal.ZERO,
                BigDecimal.ZERO).getQuantity());
    }

    @Test
    void liveFillRetainsItsTradeTimeWhenOptionalExecutedActionRiskBookkeepingIsDisabled() {
        Instant fillTime = Instant.parse("2026-10-23T00:00:00Z");
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.getRisk().setEnabled(false);
        var repository = new TradingStateRepository(tempDir.resolve("risk-disabled-memory.json"), store, "live", "BTC-USDT");
        var risk = new RiskControlService(properties, repository, Clock.fixed(fillTime, ZoneOffset.UTC),
                new TradingRiskConfiguration().tradingRiskRules());
        applyBuyFill(store, "1", fillTime);
        risk.recordReconciledAction(TradingAction.BUY, fillTime);
        assertEquals(fillTime.toString(), repository.getState().getRiskState().getLastTradeTime());
        assertEquals(0, repository.getState().getRiskState().getConsecutiveOpenActions());
    }

    @Test
    void inMemoryAdapterPreservesCommittedTradeTimeAcrossReplaysLateFillsAndStaleRiskSaves() {
        TradingFinancialStateStore memory = new InMemoryTradingFinancialStateStore();
        Instant firstTime = Instant.parse("2026-10-23T00:00:00Z");
        TradingRiskState stale = memory.getOrCreateRiskState("live", new TradingRiskState().setConsecutiveOpenActions(2));
        applyBuyFill(memory, "1", firstTime);
        assertFalse(applyBuyFill(memory, "1", firstTime.plusSeconds(60)).changed());
        assertEquals(firstTime.toString(), memory.getOrCreateRiskState("live", null).getLastTradeTime());
        applyBuyFill(memory, "2", firstTime.plusSeconds(30));
        applyBuyFill(memory, "3", firstTime.minusSeconds(30));
        assertEquals(firstTime.plusSeconds(30).toString(), memory.saveRiskState("live", stale).getLastTradeTime());
        assertEquals(2, memory.getOrCreateRiskState("live", null).getConsecutiveOpenActions());
    }

    private static SpotFillApplication applyBuyFill(TradingFinancialStateStore target, String cumulative, Instant time) {
        BigDecimal quantity = new BigDecimal(cumulative);
        return target.applyCumulativeSpotFill(1L, "live", "BTC-USDT", "buy", quantity, quantity,
                quantity.multiply(BigDecimal.TEN), BigDecimal.TEN, BigDecimal.ZERO, "USDT", "filled", time);
    }

    private long riskVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM okx_risk_state WHERE account_scope = 'live'", Long.class);
    }

    @Test
    void concurrentStateReadCannotHoldMemoryLockWhileWaitingForSettlementRow() throws Exception {
        var readerEnteredDatabase = new CountDownLatch(1);
        TradingFinancialStateStore observedStore = mock(TradingFinancialStateStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("financial-state-reader")) {
                readerEnteredDatabase.countDown();
            }
            return store.getOrCreatePosition(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
        }).when(observedStore).getOrCreatePosition(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
        var repository = new TradingStateRepository(tempDir.resolve("memory.json"), observedStore, "live", "BTC-USDT");
        repository.getState();
        var rowLocked = new CountDownLatch(1);
        jdbcTemplate.execute("SET DEFAULT_LOCK_TIMEOUT 1000");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var settlement = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                store.applyCumulativeSpotFill(1, "live", "BTC-USDT", "buy", BigDecimal.ONE, BigDecimal.ONE,
                        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, "USDT", "filled", Instant.now());
                rowLocked.countDown();
                try {
                    assertTrue(readerEnteredDatabase.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                // The real settlement risk update re-enters getState before
                // its outer transaction releases the position row lock.
                var state = repository.getState();
                repository.recordRiskState(state.getRiskState().setConsecutiveOpenActions(1));
                return state;
            }));
            assertTrue(rowLocked.await(5, TimeUnit.SECONDS));
            var reader = executor.submit(() -> {
                Thread.currentThread().setName("financial-state-reader");
                return repository.getState();
            });

            assertDecimal("1", settlement.get(5, TimeUnit.SECONDS).getTrackedBaseAmount());
            assertDecimal("1", reader.get(5, TimeUnit.SECONDS).getTrackedBaseAmount());
            assertEquals(1, repository.getState().getRiskState().getConsecutiveOpenActions());
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_order_fill_ledger", Integer.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            jdbcTemplate.execute("SET DEFAULT_LOCK_TIMEOUT 2000");
        }
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    /**
     * Loads only the two mapper seams exercised here. Keeping this as a
     * persistence slice prevents unrelated web/application beans from making
     * the financial-state regression test brittle.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceTestConfiguration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL(
                    "jdbc:h2:mem:financial_state;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            );
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/trading/TradingFinancialStateMapper.xml"),
                    new ClassPathResource("mapper/trading/FundSafetyMapper.xml")
            );
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<TradingFinancialStateMapper> tradingFinancialStateMapper(
                SqlSessionFactory sqlSessionFactory
        ) {
            MapperFactoryBean<TradingFinancialStateMapper> factory =
                    new MapperFactoryBean<>(TradingFinancialStateMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        MapperFactoryBean<FundSafetyMapper> fundSafetyMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<FundSafetyMapper> factory = new MapperFactoryBean<>(FundSafetyMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        TradingFinancialStateStore tradingFinancialStateStore(TradingFinancialStateMapper mapper) {
            return new MyBatisTradingFinancialStateStore(mapper);
        }

        @Bean
        FundSafetyRepository fundSafetyRepository(FundSafetyMapper mapper) {
            return new MyBatisFundSafetyRepository(mapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
