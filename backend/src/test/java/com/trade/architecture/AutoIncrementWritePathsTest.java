package com.trade.architecture;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real mapper SQL, initialization rollback and row locks without starting application tasks. */
class AutoIncrementWritePathsTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Map<String, Object> REPOSITORIES = new ConcurrentHashMap<>();
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate session;
    private static TransactionTemplate transactions;
    private static boolean mysql;
    private static long orderId;
    private static long okxDecisionId;
    private static long polymarketDecisionId;

    @BeforeAll
    static void initialize() throws Exception {
        // Optional MySQL runs must target an explicitly disposable database on this computer.
        String mysqlUrl = System.getProperty("trade.test.mysql-url");
        mysql = mysqlUrl != null;
        DataSource dataSource;
        if (mysql) {
            assertTrue(mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:\\d+/codex_id_[a-zA-Z0-9_]+(?:\\?.*)?"),
                    "Only a local codex_id_* test database is allowed");
            dataSource = new DriverManagerDataSource(mysqlUrl
                    + (mysqlUrl.contains("?") ? "&" : "?")
                    + "sessionVariables=information_schema_stats_expiry=0&allowPublicKeyRetrieval=true&useSSL=false",
                    "root", "");
        } else {
            var h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:id_writes_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource = h2;
        }
        try (var connection = dataSource.getConnection()) {
            for (String module : new String[]{"ai", "trading", "polymarket", "textgame", "marketplace", "weibo", "x"}) {
                byte[] bytes;
                try (var input = new ClassPathResource("db/schema/" + module + "/schema.sql").getInputStream()) {
                    bytes = input.readAllBytes();
                }
                String sql = new String(bytes, StandardCharsets.UTF_8);
                if (!mysql) {
                    sql = sql.replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4"
                            + "\\s+COLLATE=utf8mb4_unicode_ci(?:\\s+COMMENT='[^']*')?", "");
                }
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        jdbc = new JdbcTemplate(dataSource);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Object parentOrder = submission("fill-parent");
        transactions.executeWithoutResult(status ->
                invoke(repository("trading.MyBatisTradingOrderRepository"), "createOrGet", parentOrder));
        orderId = jdbc.queryForObject("SELECT id FROM okx_orders WHERE idempotency_key = 'fill-parent'", Long.class);
        jdbc.update("INSERT INTO okx_decision_runs(started_at, inst_id) VALUES (?, ?)", Timestamp.from(NOW), "BTC-USDT");
        okxDecisionId = jdbc.queryForObject("SELECT MAX(id) FROM okx_decision_runs", Long.class);
        jdbc.update("INSERT INTO polymarket_decision_runs(started_at) VALUES (?)", Timestamp.from(NOW));
        polymarketDecisionId = jdbc.queryForObject("SELECT MAX(id) FROM polymarket_decision_runs", Long.class);
    }

    record Guard(String table, String repository, String method, Object[] args) {
        @Override public String toString() { return table; }
    }

    static Stream<Guard> guards() throws Exception {
        Object risk = Class.forName("com.trade.trading.domain.model.TradingRiskState").getConstructor().newInstance();
        return Stream.of(
                guard("okx_trading_leader_lease", "trading.MyBatisTradingLeaderLeaseRepository",
                        "acquireOrRenew", "id-test", "owner", Duration.ofMinutes(1)),
                guard("okx_position_state", "trading.MyBatisTradingFinancialStateStore",
                        "getOrCreatePosition", "id-test", "BTC-USDT", BigDecimal.ZERO, BigDecimal.ZERO),
                guard("okx_risk_state", "trading.MyBatisTradingFinancialStateStore",
                        "getOrCreateRiskState", "id-test", risk),
                guard("okx_order_fill_ledger", "trading.MyBatisTradingFinancialStateStore",
                        "applyCumulativeSpotFill", orderId, "fill-test", "BTC-USDT", "buy",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.ZERO, "USDT", "filled", NOW),
                guard("okx_fund_safety_state", "trading.MyBatisFundSafetyRepository", "getOrCreate", "id-test"),
                guard("okx_orders", "trading.MyBatisTradingOrderRepository", "createOrGet", submission("id-test")),
                guard("okx_paper_account_heads", "trading.MyBatisPaperAccountStore", "lock", "BTC-USDT"),
                guard("okx_ai_strategy_guard", "trading.MyBatisTradingAiReviewStore",
                        "claim", "id-test", "review-id-test", NOW, NOW.plusSeconds(86400), NOW.plusSeconds(3600)),
                guard("polymarket_execution_guard", "polymarket.MyBatisPolymarketExecutionGuard", "reserve", 123L),
                guard("x_account_gate", "x.MyBatisXPostRepository", "lock", "id-test"),
                guard("weibo_account_gate", "weibo.MyBatisWeiboPostRepository", "lock", "id-test"),
                guard("x_review_polling", "x.MyBatisXReviewDeliveryStore", "acquirePolling",
                        777L, "owner", NOW, NOW.plusSeconds(60)),
                guard("weibo_review_polling", "weibo.MyBatisWeiboReviewDeliveryStore", "acquirePolling",
                        777L, "owner", NOW, NOW.plusSeconds(60))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guards")
    void committedInitializationIsReusedButRollbackIsRetried(Guard guard) {
        Object repository = repository(guard.repository());
        int initialCount = count(guard.table());
        transactions.executeWithoutResult(status -> {
            invoke(repository, guard.method(), guard.args());
            status.setRollbackOnly();
        });
        assertEquals(initialCount, count(guard.table()), "rolled-back rows must not survive");
        transactions.executeWithoutResult(status -> invoke(repository, guard.method(), guard.args()));
        assertEquals(initialCount + 1, count(guard.table()), "rollback must not mark a missing row initialized");
        long nextId = nextIdentity(guard.table());
        for (int i = 0; i < 20; i++) {
            transactions.executeWithoutResult(status -> invoke(repository, guard.method(), guard.args()));
        }
        assertEquals(initialCount + 1, count(guard.table()));
        assertEquals(nextId, nextIdentity(guard.table()), "repeated initialization must not allocate IDs");
    }

    record Audit(String table, String mapper, String method, String rowType, String property, String column) {
        @Override public String toString() { return table; }
    }

    static Stream<Audit> audits() {
        return Stream.of(
                audit("okx_ai_requests", "trading.AiDecisionAuditMapper", "upsertAiRequest", "trading.AiRequestRow", "promptText", "prompt_text"),
                audit("okx_ai_responses", "trading.AiDecisionAuditMapper", "upsertAiResponse", "trading.AiResponseRow", "rawResponse", "raw_response"),
                audit("okx_order_executions", "trading.AiDecisionAuditMapper", "upsertOrderExecution", "trading.OrderExecutionRow", "executionStatus", "execution_status"),
                audit("polymarket_ai_requests", "polymarket.PolymarketDecisionAuditMapper", "upsertAiRequest", "polymarket.PolymarketDecisionAuditRow", "promptText", "prompt_text"),
                audit("polymarket_ai_responses", "polymarket.PolymarketDecisionAuditMapper", "upsertAiResponse", "polymarket.PolymarketDecisionAuditRow", "rawResponse", "raw_response"),
                audit("polymarket_order_executions", "polymarket.PolymarketDecisionAuditMapper", "upsertOrderExecution", "polymarket.PolymarketDecisionAuditRow", "executionStatus", "execution_status"),
                audit("weibo_account_token", "weibo.WeiboMapper", "upsertAccountToken", "weibo.WeiboAccountTokenRow", "accessToken", "access_token")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("audits")
    void auditChangesAndUnchangedReplaysDoNotAllocateIds(Audit audit) throws Exception {
        Object row = Class.forName(type(audit.rowType())).getConstructor().newInstance();
        var values = new HashMap<String, Object>();
        values.put("decisionRunId", audit.table().startsWith("polymarket_") ? polymarketDecisionId : okxDecisionId);
        values.put("instId", "BTC-USDT");
        values.put("receivedAt", Timestamp.from(NOW));
        values.put("completedAt", Timestamp.from(NOW));
        values.put("aiParametersJson", "{}");
        values.put("uid", "id-test");
        values.put("expiresAt", Timestamp.from(NOW.plusSeconds(3600)));
        var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(row);
        values.forEach((key, value) -> { if (meta.hasSetter(key)) meta.setValue(key, value); });
        meta.setValue(audit.property(), "initial");
        Object mapper = session.getMapper(Class.forName(type(audit.mapper())));
        transactions.executeWithoutResult(status -> invoke(mapper, audit.method(), row));
        long id = jdbc.queryForObject("SELECT id FROM " + audit.table(), Long.class);
        long nextId = nextIdentity(audit.table());
        meta.setValue(audit.property(), "updated");
        for (int i = 0; i < 20; i++) {
            transactions.executeWithoutResult(status -> invoke(mapper, audit.method(), row));
        }
        assertEquals(1, count(audit.table()));
        assertEquals(id, jdbc.queryForObject("SELECT id FROM " + audit.table(), Long.class));
        assertEquals("updated", jdbc.queryForObject("SELECT " + audit.column() + " FROM " + audit.table(), String.class));
        assertEquals(nextId, nextIdentity(audit.table()), "updates, including unchanged values, must preserve the sequence");
    }

    @Test
    void missingRowInvalidatesACommittedInitializationMarker() {
        Object repository = repository("x.MyBatisXPostRepository");
        String key = "restored-gate-test";
        transactions.executeWithoutResult(status -> invoke(repository, "lock", key));
        jdbc.update("DELETE FROM x_account_gate WHERE user_id = ?", key);
        transactions.executeWithoutResult(status -> invoke(repository, "lock", key));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_account_gate WHERE user_id = ?", Integer.class, key));
        long nextId = nextIdentity("x_account_gate");
        transactions.executeWithoutResult(status -> invoke(repository, "lock", key));
        assertEquals(nextId, nextIdentity("x_account_gate"));
    }

    @Test
    void warmAccountGateStillHoldsItsDatabaseLock() throws Exception {
        Object repository = repository("x.MyBatisXPostRepository");
        String key = "warm-lock-test";
        transactions.executeWithoutResult(status -> invoke(repository, "lock", key));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempted = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transactions.executeWithoutResult(status -> {
                invoke(repository, "lock", key);
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> transactions.executeWithoutResult(status -> {
                secondAttempted.countDown();
                invoke(repository, "lock", key);
                secondEntered.countDown();
            }));
            try {
                assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
                assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS), "the second transaction must wait for the row lock");
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(0, secondEntered.getCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    void independentInstancesCanConcurrentlyInitializeTheSameGate() throws Exception {
        Object firstRepository = newRepository("x.MyBatisXPostRepository");
        Object secondRepository = newRepository("x.MyBatisXPostRepository");
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 12; i++) {
                String key = "cold-lock-test-" + i;
                var start = new CountDownLatch(1);
                var first = pool.submit(() -> {
                    await(start);
                    transactions.executeWithoutResult(status -> invoke(firstRepository, "lock", key));
                });
                var second = pool.submit(() -> {
                    await(start);
                    transactions.executeWithoutResult(status -> invoke(secondRepository, "lock", key));
                });
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_account_gate WHERE user_id = ?", Integer.class, key));
            }
        }
    }

    private static long nextIdentity(String table) {
        return mysql
                ? jdbc.queryForObject("SELECT AUTO_INCREMENT FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = ?", Long.class, table)
                : jdbc.queryForObject("SELECT identity_base FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'id'", Long.class, table);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Object repository(String name) {
        return REPOSITORIES.computeIfAbsent(name, AutoIncrementWritePathsTest::newRepository);
    }

    private static Object newRepository(String name) {
        try {
            var constructor = Class.forName(type(name)).getConstructors()[0];
            assertEquals(1, constructor.getParameterCount());
            return constructor.newInstance(session.getMapper(constructor.getParameterTypes()[0]));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object submission(String key) throws Exception {
        var constructor = Class.forName("com.trade.trading.domain.order.OrderSubmission").getConstructors()[0];
        return constructor.newInstance(key, "client-" + key, "decision-" + key, "strategy", "BTC-USDT",
                "BUY", "buy", "cash", "market", "quote_ccy", BigDecimal.ONE);
    }

    private static String type(String shortName) {
        int dot = shortName.indexOf('.');
        return "com.trade." + shortName.substring(0, dot) + ".infrastructure.persistence." + shortName.substring(dot + 1);
    }

    private static Guard guard(String table, String repository, String method, Object... args) {
        return new Guard(table, repository, method, args);
    }

    private static Audit audit(String table, String mapper, String method, String row, String property, String column) {
        return new Audit(table, mapper, method, row, property, column);
    }

    private static Object invoke(Object target, String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(target, method, args);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}

