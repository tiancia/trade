package com.trade.weibo.infrastructure.persistence;

import com.trade.weibo.application.port.WeiboPostRepository;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.domain.model.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(MyBatisWeiboPostRepositoryTest.Config.class)
class MyBatisWeiboPostRepositoryTest {
    @Autowired private WeiboPostRepository posts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WeiboAccountTokenRepository tokens;
    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");
    private final Instant day = now.truncatedTo(ChronoUnit.DAYS);
    private final WeiboWorkflowPolicy policy = new WeiboWorkflowPolicy(true, true, true, "uid", 280, 5, 3,
            Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));

    @BeforeEach
    void schema() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS weibo_publish_attempt");
        jdbc.execute("DROP TABLE IF EXISTS weibo_post_history");
        jdbc.execute("DROP TABLE IF EXISTS weibo_post");
        jdbc.execute("DROP TABLE IF EXISTS weibo_account_gate");
        jdbc.execute("DROP TABLE IF EXISTS weibo_account_token");
        jdbc.execute("CREATE TABLE weibo_account_token (uid VARCHAR(64) PRIMARY KEY, access_token VARCHAR(1000),"
                + " expires_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + " updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        // Execute the production migration itself; only MySQL storage-engine options are omitted for H2.
        String sql;
        try (var input = new ClassPathResource("db/migration/migration_add_weibo_workflow.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        sql = sql.replaceAll("(?m)^--.*$", "")
                .replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    @Test
    void tokenLookupUsesBoundUidEvenWhenAnotherAccountWasAuthorizedLater() {
        tokens.upsert("uid", "test-bound-token", now.plusSeconds(3600));
        tokens.upsert("other", "test-other-token", now.plusSeconds(3600));
        jdbc.update("UPDATE weibo_account_token SET updated_at = ? WHERE uid='other'", java.sql.Timestamp.from(now.plusSeconds(1)));
        assertEquals("test-bound-token", tokens.findValidForUid("uid", now).orElseThrow().accessToken());
        assertTrue(tokens.findValidForUid("missing", now).isEmpty());
        assertTrue(tokens.findValidForUid("uid", now.plusSeconds(3600)).isEmpty());
    }

    @Test
    void restartRehydrationCasAndAuditPreserveExactVersions() {
        WeiboPost initial = reserved("1");
        assertTrue(posts.reserveGeneration(initial, day, 5));
        assertEquals(initial, posts.find(initial.id()).orElseThrow());
        assertFalse(posts.reserveGeneration(reserved("1"), day, 5));
        WeiboPost pending = initial.generated(new GeneratedComment("第一稿😀", "来源核实说明"), now, 280);
        assertTrue(posts.save(pending, 0));
        WeiboPost approved = pending.review(1, true, "reviewer", "checked", now, now);
        assertTrue(posts.save(approved, 1));
        WeiboPost edit = pending.revise("试图覆盖批准", now, 280);
        assertFalse(posts.save(edit, 1));
        WeiboPost claimed = approved.claim(now);
        assertTrue(posts.claimPublishing(claimed, 2, day, 3, Duration.ofMinutes(30)));
        assertFalse(posts.claimPublishing(approved.claim(now), 2, day, 3, Duration.ofMinutes(30)));
        WeiboPost finished = claimed.finish(WeiboPostStatus.PUBLISHED, "weibo-1", null, now);
        assertTrue(posts.finishPublishing(finished, 3));
        assertEquals(finished, posts.find(initial.id()).orElseThrow());
        assertEquals(5, posts.history(initial.id()).size());
        assertEquals("第一稿😀", posts.history(initial.id()).getFirst().body());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM weibo_publish_attempt", String.class));
        assertTrue(posts.actionable(100).isEmpty());
    }

    @Test
    void accountQuotasAndIntervalIncludeFailedAttempts() {
        WeiboPost first = approve(reserved("1"));
        WeiboPost claimed = first.claim(now);
        assertTrue(posts.claimPublishing(claimed, first.revision(), day, 3, Duration.ofMinutes(30)));
        assertTrue(posts.finishPublishing(claimed.finish(WeiboPostStatus.UNKNOWN, null, "timeout", now), claimed.revision()));
        WeiboPost second = approve(reserved("2"));
        assertFalse(posts.claimPublishing(second.claim(now.plusSeconds(10)), second.revision(), day, 3, Duration.ofMinutes(30)));
        assertFalse(posts.claimPublishing(second.claim(now.plusSeconds(1801)), second.revision(), day, 1, Duration.ofMinutes(30)));
        assertTrue(posts.claimPublishing(second.claim(now.plusSeconds(1801)), second.revision(), day, 3, Duration.ofMinutes(30)));
        assertFalse(posts.reserveGeneration(reserved("3"), day, 2));
    }

    @Test
    void concurrentProcessesCannotExceedDailyReservationsOrDoubleClaim() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return posts.reserveGeneration(reserved("a"), day, 1); });
            var b = executor.submit(() -> { start.await(); return posts.reserveGeneration(reserved("b"), day, 1); });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        WeiboPost initial = posts.recent(1).getFirst();
        WeiboPost pending = initial.generated(new GeneratedComment("正文", "依据"), now, 280);
        assertTrue(posts.save(pending, initial.revision()));
        WeiboPost approved = pending.review(1, true, "reviewer", null, now, now);
        assertTrue(posts.save(approved, pending.revision()));
        CountDownLatch claimStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { claimStart.await(); return posts.claimPublishing(approved.claim(now), approved.revision(), day, 3, Duration.ofMinutes(30)); });
            var b = executor.submit(() -> { claimStart.await(); return posts.claimPublishing(approved.claim(now), approved.revision(), day, 3, Duration.ofMinutes(30)); });
            claimStart.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weibo_publish_attempt", Integer.class));
    }

    @Test
    void auditFailureRollsBackPostTransition() {
        WeiboPost initial = reserved("1");
        assertTrue(posts.reserveGeneration(initial, day, 5));
        // Force a history unique-key failure after the post UPDATE.
        jdbc.update("INSERT INTO weibo_post_history (id, revision, content_version, status, updated_at) VALUES (?,1,1,'GENERATING',?)",
                initial.id(), java.sql.Timestamp.from(now));
        assertThrows(RuntimeException.class,
                () -> posts.save(initial.generated(new GeneratedComment("正文", "依据"), now, 280), 0));
        assertEquals(WeiboPostStatus.GENERATING, posts.find(initial.id()).orElseThrow().status());
        assertEquals(0, posts.find(initial.id()).orElseThrow().revision());
    }

    private WeiboPost reserved(String identity) {
        return WeiboPost.generating("uid", new HotEvent("事件", "https://example.test/" + identity,
                "来源摘要", now, now), now, policy);
    }

    private WeiboPost approve(WeiboPost initial) {
        assertTrue(posts.reserveGeneration(initial, day, 5));
        WeiboPost pending = initial.generated(new GeneratedComment("正文", "依据"), now, 280);
        assertTrue(posts.save(pending, initial.revision()));
        WeiboPost approved = pending.review(1, true, "reviewer", null, now, now);
        assertTrue(posts.save(approved, pending.revision()));
        return approved;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:weibo_workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/weibo/WeiboPostMapper.xml"),
                    new ClassPathResource("mapper/weibo/WeiboMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<WeiboPostMapper> mapper(SqlSessionFactory sessions) {
            MapperFactoryBean<WeiboPostMapper> mapper = new MapperFactoryBean<>(WeiboPostMapper.class);
            mapper.setSqlSessionFactory(sessions);
            return mapper;
        }
        @Bean WeiboPostRepository repository(WeiboPostMapper mapper) { return new MyBatisWeiboPostRepository(mapper); }
        @Bean MapperFactoryBean<WeiboMapper> tokenMapper(SqlSessionFactory sessions) {
            MapperFactoryBean<WeiboMapper> mapper = new MapperFactoryBean<>(WeiboMapper.class);
            mapper.setSqlSessionFactory(sessions);
            return mapper;
        }
        @Bean WeiboAccountTokenRepository tokens(WeiboMapper mapper) { return new MyBatisWeiboAccountTokenRepository(mapper); }
    }
}
