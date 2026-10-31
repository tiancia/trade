package com.trade.automation.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationStrategyTest {
    @TempDir Path temporary;

    @Test void emptyDatabaseMigratesToCurrentSchemaExactlyOnceAndKeepsChecksums() {
        var data = dataSource(); var flyway = flyway(data);
        new DatabaseMigrationStrategy().migrate(flyway);
        var jdbc = new JdbcTemplate(data);
        assertEquals(57, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'", Integer.class));
        assertEquals("HALTED", jdbc.queryForObject("SELECT status FROM okx_fund_safety_state WHERE account_scope='live'", String.class));
        assertEquals(List.of("1", "2", "3"), jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE checksum IS NOT NULL AND success", Integer.class));
        jdbc.update("UPDATE okx_fund_safety_state SET reason='preserve operator review' WHERE account_scope='live'");
        new DatabaseMigrationStrategy().migrate(flyway(data));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class));
        assertEquals("preserve operator review", jdbc.queryForObject("SELECT reason FROM okx_fund_safety_state WHERE account_scope='live'", String.class));
    }

    @Test void recognizedLegacyDatabaseIsBaselinedThenUpgradedWithoutReplacingItsData() throws Exception {
        var data = dataSource(); script(data, LegacySchemaVerifier.read(LegacySchemaVerifier.INITIAL_SCHEMA));
        var jdbc = new JdbcTemplate(data);
        jdbc.update("UPDATE okx_fund_safety_state SET reason='legacy review' WHERE account_scope='live'");
        new DatabaseMigrationStrategy().migrate(flyway(data));
        assertEquals(List.of("0", "1", "2", "3"), jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class));
        assertEquals("legacy review", jdbc.queryForObject("SELECT reason FROM okx_fund_safety_state WHERE account_scope='live'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='okx_risk_state' AND column_name IN ('open_trade_realized_pnl','closed_trade_accounting_version')", Integer.class));
    }

    @Test void alreadyAppliedManualUpgradeIsAdoptedAndItsSelectionSurvives() throws Exception {
        var data = dataSource();
        for (String module : List.of("ai", "trading", "polymarket", "textgame", "marketplace", "weibo", "x")) {
            try (var connection = data.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema/" + module + "/schema.sql"));
            }
        }
        var jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO okx_strategy_selection(account_scope,inst_id,strategy_id,revision) VALUES('paper','BTC-USDT','operator-choice',8)");
        new DatabaseMigrationStrategy().migrate(flyway(data));
        assertEquals("operator-choice", jdbc.queryForObject("SELECT strategy_id FROM okx_strategy_selection WHERE account_scope='paper'", String.class));
        assertEquals(8, jdbc.queryForObject("SELECT revision FROM okx_strategy_selection WHERE account_scope='paper'", Integer.class));
    }

    @Test void migrationChainMatchesReferenceColumnsKeysAndImageVersion() throws Exception {
        var migrated = dataSource(); var reference = dataSource();
        var migration = flyway(migrated);
        new DatabaseMigrationStrategy().migrate(migration);
        for (String module : List.of("automation", "ai", "trading", "polymarket", "textgame", "marketplace", "weibo", "x")) {
            script(reference, LegacySchemaVerifier.read("db/schema/" + module + "/schema.sql"));
        }
        String columns = "SELECT table_name,column_name,data_type,character_maximum_length,numeric_precision,numeric_scale,is_nullable,column_default,is_identity FROM information_schema.columns WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY table_name,column_name";
        var expected = new JdbcTemplate(reference).queryForList(columns);
        var actual = new JdbcTemplate(migrated).queryForList(columns);
        assertEquals(expected.size(), actual.size(), "Reference and migrated column counts");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i));
        try (var connection = migrated.getConnection()) {
            for (String module : List.of("automation", "ai", "trading", "polymarket", "textgame", "marketplace", "weibo", "x")) {
                LegacySchemaVerifier.verifyExistingDefinitions(connection, LegacySchemaVerifier.read("db/schema/" + module + "/schema.sql"));
            }
        }
        String version = java.util.Arrays.stream(migration.info().all()).map(info -> info.getVersion())
                .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElseThrow().toString();
        assertTrue(Files.readString(Path.of("Dockerfile")).contains("LABEL com.trade.database.version=\"" + version + "\""),
                "Update the backend image schema label whenever a migration is added");
    }

    @Test void existingSellCheckpointIsBackfilledOnceIncludingSignedQuoteFee() throws Exception {
        var data = dataSource(); script(data, LegacySchemaVerifier.read(LegacySchemaVerifier.INITIAL_SCHEMA));
        var jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO okx_orders(idempotency_key,client_order_id,inst_id,action,side,requested_size,status,created_at,updated_at) VALUES('settled','settled','BTC-USDT','SELL','sell',2,'FILLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        long order = jdbc.queryForObject("SELECT id FROM okx_orders WHERE idempotency_key='settled'", Long.class);
        jdbc.update("INSERT INTO okx_order_fill_ledger(order_id,side,cumulative_filled_size,applied_position_quantity,applied_quote_cost,average_fill_price,fee,fee_ccy,version,created_at,updated_at) VALUES(?,'sell',2,2,0,100,-1,'USDT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order);
        new DatabaseMigrationStrategy().migrate(flyway(data));
        assertEquals(0, new java.math.BigDecimal("199").compareTo(jdbc.queryForObject("SELECT applied_quote_cost FROM okx_order_fill_ledger WHERE order_id=?", java.math.BigDecimal.class, order)));
        jdbc.update("UPDATE okx_order_fill_ledger SET applied_quote_cost=200 WHERE order_id=?", order);
        new DatabaseMigrationStrategy().migrate(flyway(data));
        assertEquals(0, new java.math.BigDecimal("200").compareTo(jdbc.queryForObject("SELECT applied_quote_cost FROM okx_order_fill_ledger WHERE order_id=?", java.math.BigDecimal.class, order)));
    }

    @Test void arbitraryOrTooOldDatabasesAreRejectedBeforeAnyBaselineWrite() throws Exception {
        for (String mismatch : List.of("foreign", "column", "unique", "type")) {
            var data = dataSource(); var jdbc = new JdbcTemplate(data);
            if (mismatch.equals("foreign")) jdbc.execute("CREATE TABLE unrelated(id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            else {
                String baseline = LegacySchemaVerifier.read(LegacySchemaVerifier.INITIAL_SCHEMA);
                if (mismatch.equals("unique")) baseline = baseline.replace("UNIQUE KEY `uk_okx_orders_client_order_id` (`client_order_id`),", "");
                script(data, baseline);
                if (mismatch.equals("column")) jdbc.execute("ALTER TABLE okx_risk_state DROP COLUMN day_start_equity");
                if (mismatch.equals("type")) jdbc.execute("ALTER TABLE okx_risk_state ALTER COLUMN current_equity VARCHAR(64)");
            }
            assertThrows(IllegalStateException.class, () -> new DatabaseMigrationStrategy().migrate(flyway(data)), mismatch);
            try (var connection = data.getConnection()) { assertFalse(LegacySchemaVerifier.tables(connection).contains("flyway_schema_history")); }
        }
    }

    @Test void invalidFeeHistoryStopsMigrationAndNeverGetsAutomaticallyRepaired() throws Exception {
        var data = dataSource(); script(data, LegacySchemaVerifier.read(LegacySchemaVerifier.INITIAL_SCHEMA));
        var jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO okx_orders(idempotency_key,client_order_id,inst_id,action,side,requested_size,status,created_at,updated_at) VALUES('legacy','legacy','BTC-USDT','SELL','sell',1,'FILLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        long order = jdbc.queryForObject("SELECT id FROM okx_orders WHERE idempotency_key='legacy'", Long.class);
        jdbc.update("INSERT INTO okx_order_fill_ledger(order_id,side,cumulative_filled_size,applied_position_quantity,applied_quote_cost,average_fill_price,fee,fee_ccy,version,created_at,updated_at) VALUES(?,'sell',1,1,0,100,-1,'OKB',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order);
        assertThrows(FlywayException.class, () -> new DatabaseMigrationStrategy().migrate(flyway(data)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success AND version='2'", Integer.class));
        assertThrows(FlywayException.class, () -> new DatabaseMigrationStrategy().migrate(flyway(data)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM okx_orders", Integer.class));
    }

    @Test void migrationChecksumChangesAndUnknownNewerVersionsBlockStartup() throws Exception {
        var data = dataSource(); new DatabaseMigrationStrategy().migrate(flyway(data));
        var jdbc = new JdbcTemplate(data);
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        assertThrows(FlywayException.class, () -> new DatabaseMigrationStrategy().migrate(flyway(data)));
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum-1 WHERE version='1'");
        Files.writeString(temporary.resolve("V4__future_schema.sql"), "CREATE TABLE future_table(id BIGINT AUTO_INCREMENT PRIMARY KEY);");
        Flyway future = Flyway.configure().dataSource(data).locations("classpath:db/migration", "filesystem:" + temporary)
                .javaMigrations(new V2__TradingRuntimeSafety()).baselineVersion("0").cleanDisabled(true)
                .ignoreMigrationPatterns(new String[0]).load();
        new DatabaseMigrationStrategy().migrate(future);
        assertThrows(FlywayException.class, () -> new DatabaseMigrationStrategy().migrate(flyway(data)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success", Integer.class));
    }

    @Test void concurrentInitializersShareOneMigrationHistory() throws Exception {
        var data = dataSource(); script(data, LegacySchemaVerifier.read(LegacySchemaVerifier.INITIAL_SCHEMA));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Void> migrate = () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                new DatabaseMigrationStrategy().migrate(flyway(data)); return null;
            };
            var first = workers.submit(migrate); var second = workers.submit(migrate);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { start.countDown(); }
        assertEquals(4, new JdbcTemplate(data).queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class));
    }

    private static Flyway flyway(DataSource data) {
        return Flyway.configure().dataSource(data).locations("classpath:db/migration")
                .javaMigrations(new V2__TradingRuntimeSafety()).baselineVersion("0").baselineOnMigrate(false)
                .cleanDisabled(true).outOfOrder(false).validateMigrationNaming(true).ignoreMigrationPatterns(new String[0]).load();
    }
    private static JdbcDataSource dataSource() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:migrations_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        data.setUser("sa"); data.setPassword(""); return data;
    }
    private static void script(DataSource data, String sql) throws Exception {
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
