package com.trade.trading.persistence;

import com.trade.trading.model.TradingPositionState;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.risk.FundSafetyRepository;
import com.trade.trading.risk.FundSafetyStatus;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(MyBatisTradingFinancialStateStoreTest.PersistenceTestConfiguration.class)
@Sql(statements = {
        "DROP TABLE IF EXISTS okx_order_fill_ledger",
        "DROP TABLE IF EXISTS okx_risk_state",
        "DROP TABLE IF EXISTS okx_position_state",
        "DROP TABLE IF EXISTS okx_fund_safety_state",
        "DROP TABLE IF EXISTS okx_orders",
        "CREATE TABLE okx_orders (id BIGINT PRIMARY KEY)",
        "CREATE TABLE okx_position_state (" +
                "account_scope VARCHAR(32) NOT NULL, inst_id VARCHAR(64) NOT NULL," +
                "position_side VARCHAR(16) NOT NULL, quantity DECIMAL(38,18) NOT NULL," +
                "average_cost DECIMAL(38,18) NOT NULL, exchange_quantity DECIMAL(38,18)," +
                "last_reconciled_at TIMESTAMP, version BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL," +
                "PRIMARY KEY(account_scope, inst_id))",
        "CREATE TABLE okx_risk_state (" +
                "account_scope VARCHAR(32) PRIMARY KEY, current_equity DECIMAL(38,18) NOT NULL," +
                "equity_high_watermark DECIMAL(38,18) NOT NULL, day_start_equity DECIMAL(38,18) NOT NULL," +
                "day_start_date VARCHAR(16), consecutive_losses INT NOT NULL, loss_cooldown_until TIMESTAMP," +
                "last_trade_time TIMESTAMP, consecutive_open_actions INT NOT NULL, last_risk_reason VARCHAR(1000)," +
                "consecutive_reconciliation_failures INT NOT NULL, last_reconciliation_at TIMESTAMP," +
                "last_reconciliation_error VARCHAR(1000), version BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
        "CREATE TABLE okx_fund_safety_state (" +
                "account_scope VARCHAR(32) PRIMARY KEY, status VARCHAR(16) NOT NULL," +
                "reason VARCHAR(1000), source VARCHAR(64), resume_reason VARCHAR(1000)," +
                "last_action_error VARCHAR(1000), halted_at TIMESTAMP, resumed_at TIMESTAMP," +
                "updated_at TIMESTAMP NOT NULL, version BIGINT NOT NULL)",
        "CREATE TABLE okx_order_fill_ledger (" +
                "order_id BIGINT PRIMARY KEY, side VARCHAR(16) NOT NULL," +
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

    @Test
    void positionCostRiskAndCumulativeFillAreDurableAndIdempotent() {
        store.getOrCreatePosition("paper", "BTC-USDT", new BigDecimal("0.1"), new BigDecimal("50000"));
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
    }

    @Test
    void fundStopSurvivesReadsAndRequiresExpectedRevisionToResume() {
        var bootstrap = fundSafetyRepository.getOrCreate("live");
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

        var resumed = fundSafetyRepository.resume("live", halted.getVersion(), "operator checked", Instant.now());
        assertEquals(FundSafetyStatus.ACTIVE, resumed.getStatus());
        assertEquals(3L, resumed.getVersion());
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
