package com.trade.trading.infrastructure.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the module's real DDL and mapper SQL offline, preserving business uniqueness and references. */
class TradingAutoIncrementSchemaTest {
    @Test
    void candleUpsertsKeepOneGeneratedIdPerInstrumentBarAndTimestamp() throws Exception {
        var dataSource = initializedDataSource();
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(dataSource);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/trading/OkxCandleCacheMapper.xml"));
            try (var session = factory.getObject().openSession(true)) {
                var mapper = session.getMapper(OkxCandleCacheMapper.class);
                long timestamp = 1710000000000L;
                mapper.upsert(candle(timestamp, "65000", "0"));
                Long firstId = jdbc.queryForObject("SELECT id FROM okx_candle_cache", Long.class);
                assertTrue(firstId > 0);

                mapper.upsert(candle(timestamp, "65001", "1"));
                assertEquals(firstId, jdbc.queryForObject("SELECT id FROM okx_candle_cache", Long.class));
                mapper.upsertBatch(List.of(
                        candle(timestamp, "65002", "1"),
                        candle(timestamp + 60000, "65003", "1")));

                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM okx_candle_cache", Integer.class));
                assertEquals(firstId, jdbc.queryForObject(
                        "SELECT id FROM okx_candle_cache WHERE ts = ?", Long.class, timestamp));
                assertTrue(jdbc.queryForObject(
                        "SELECT id FROM okx_candle_cache WHERE ts = ?", Long.class, timestamp + 60000) > firstId);
                var rows = mapper.findRange("BTC-USDT", "1m", timestamp, timestamp + 60000);
                assertEquals(2, rows.size());
                assertEquals(0, new BigDecimal("65002").compareTo(rows.getFirst().getClose()));
                assertEquals("1", rows.getFirst().getConfirm());
            }
        }
    }

    @Test
    void backtestIdsAreGeneratedWhileRunIdentityAndCascadeReferencesRemainEnforced() throws Exception {
        var dataSource = initializedDataSource();
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(dataSource);
            insertBacktestRun(jdbc, "run-one");
            insertBacktestRun(jdbc, "run-two");
            Long firstId = jdbc.queryForObject(
                    "SELECT id FROM okx_backtest_runs WHERE run_id = 'run-one'", Long.class);
            assertTrue(firstId > 0);
            assertTrue(jdbc.queryForObject(
                    "SELECT id FROM okx_backtest_runs WHERE run_id = 'run-two'", Long.class) > firstId);
            assertThrows(DuplicateKeyException.class, () -> insertBacktestRun(jdbc, "run-one"));

            jdbc.update("INSERT INTO okx_backtest_metrics (run_id, trade_count) VALUES ('run-one', 1)");
            jdbc.update("""
                    INSERT INTO okx_backtest_trades (run_id, action, ts)
                    VALUES ('run-one', 'BUY', CURRENT_TIMESTAMP)
                    """);
            assertTrue(jdbc.queryForObject("SELECT id FROM okx_backtest_metrics", Long.class) > 0);
            assertTrue(jdbc.queryForObject("SELECT id FROM okx_backtest_trades", Long.class) > 0);
            assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "INSERT INTO okx_backtest_metrics (run_id) VALUES ('run-one')"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO okx_backtest_metrics (run_id) VALUES ('missing-run')"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO okx_backtest_trades (run_id, action, ts)
                    VALUES ('missing-run', 'BUY', CURRENT_TIMESTAMP)
                    """));

            jdbc.update("DELETE FROM okx_backtest_runs WHERE run_id = 'run-one'");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_backtest_metrics", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM okx_backtest_trades", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM okx_backtest_runs", Integer.class));
        }
    }

    private static void insertBacktestRun(JdbcTemplate jdbc, String runId) {
        jdbc.update("""
                INSERT INTO okx_backtest_runs (
                    run_id, created_at, status, strategy_id, inst_id, bar, from_ts, to_ts
                ) VALUES (?, CURRENT_TIMESTAMP, 'COMPLETED', 'threshold', 'BTC-USDT', '1m',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, runId);
    }

    private static OkxCandleCacheRow candle(long timestamp, String close, String confirm) {
        return new OkxCandleCacheRow().setInstId("BTC-USDT").setBar("1m").setTs(timestamp)
                .setClose(new BigDecimal(close)).setConfirm(confirm);
    }

    private static JdbcDataSource initializedDataSource() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:trading_ids_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var input = new ClassPathResource("db/schema/trading/schema.sql").getInputStream();
             var connection = dataSource.getConnection()) {
            // Omit only table options that are specific to MySQL; execute the actual module schema.
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4"
                            + "\\s+COLLATE=utf8mb4_unicode_ci(?:\\s+COMMENT='[^']*')?", "");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        return dataSource;
    }
}
