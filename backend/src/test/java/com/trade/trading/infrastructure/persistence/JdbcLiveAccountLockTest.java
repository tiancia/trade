package com.trade.trading.infrastructure.persistence;

import com.trade.automation.infrastructure.migration.DatabaseMigrationStrategy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class JdbcLiveAccountLockTest {
    @Test
    void separateConnectionsSerializeExchangeActionsButAllowEmergencyHaltToCommit() throws Exception {
        String port = System.getProperty("trade.test.account-mysql-port");
        var data = port == null
                ? new DriverManagerDataSource("jdbc:h2:mem:actions_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + Integer.parseInt(port) + "/codex_account_recovery", "root", "");
        var flyway = Flyway.configure().dataSource(data).locations("classpath:db/migration").load();
        new DatabaseMigrationStrategy().migrate(flyway);
        new DatabaseMigrationStrategy().migrate(flyway);
        var jdbc = new JdbcTemplate(data);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM okx_account_action_guard WHERE account_scope='live'", Integer.class));
        int version = jdbc.queryForObject("SELECT version FROM okx_fund_safety_state WHERE account_scope='live'", Integer.class);
        var first = new JdbcLiveAccountLock(data);
        var second = new JdbcLiveAccountLock(data);
        var inside = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var submission = workers.submit(() -> first.withLock(() -> {
                inside.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                throw new IllegalStateException("exchange response lost");
            }));
            try {
                assertTrue(inside.await(5, TimeUnit.SECONDS));
                var recovery = workers.submit(() -> {
                    waiting.countDown();
                    return second.withLock(() -> jdbc.queryForObject(
                            "SELECT version FROM okx_fund_safety_state WHERE account_scope='live'", Integer.class));
                });
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> recovery.get(200, TimeUnit.MILLISECONDS));
                assertEquals(1, jdbc.update("UPDATE okx_fund_safety_state SET status='HALTED', version=version+1 WHERE account_scope='live'"));
                release.countDown();
                assertEquals("exchange response lost", assertThrows(ExecutionException.class,
                        () -> submission.get(5, TimeUnit.SECONDS)).getCause().getMessage());
                assertEquals(version + 1, recovery.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }
}
