package com.trade.trading.infrastructure.persistence;

import com.trade.trading.application.port.TradingAiReviewStore;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TradingAiReviewPersistenceTest.Config.class)
class TradingAiReviewPersistenceTest {
    @Autowired TradingAiReviewStore store;
    @Autowired DataSource dataSource;
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @BeforeEach void schema() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS okx_ai_strategy_review");
        jdbc.execute("DROP TABLE IF EXISTS okx_ai_strategy_guard");
        try (var input = new ClassPathResource("db/upgrade/trading/2026-10-28-ai-strategy-advisor.sql").getInputStream()) {
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll(
                    "(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
            new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(dataSource);
        }
    }

    @Test void concurrentWorkersClaimOnlyOnePaidAssessmentAndPersistCooldownAfterFailure() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return claim("first", NOW); });
            var second = executor.submit(() -> { start.await(); return claim("second", NOW); });
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        var only = store.recent("paper:BTC-USDT", 10).getFirst();
        store.complete(only.reviewId(), "FAILED", "no response", NOW.plusSeconds(1));
        assertFalse(claim("third", NOW.plusSeconds(60)));
        assertTrue(claim("next", NOW.plusSeconds(3600)));
        assertEquals(2, store.recent("paper:BTC-USDT", 10).size());
        assertTrue(store.claim("live:BTC-USDT", "live", NOW, NOW.plusSeconds(3600), NOW.plusSeconds(300)));
    }

    @Test void intentCannotBeReplayedAndExpiredProposalCannotApply() {
        assertTrue(claim("r1", NOW));
        store.proposed("r1", "{\"candles\":[]}", "{\"action\":\"HOLD\"}");
        assertTrue(store.beginApplying("r1", NOW.plusSeconds(1)));
        assertFalse(store.beginApplying("r1", NOW.plusSeconds(2)));
        store.complete("r1", "APPLIED", "tuned", NOW.plusSeconds(3));
        assertThrows(IllegalStateException.class, () -> store.complete("r1", "FAILED", "overwrite", NOW));
        assertEquals("APPLIED", store.recent("paper:BTC-USDT", 10).getFirst().outcome());
        assertTrue(claim("r2", NOW.plusSeconds(3600)));
        store.proposed("r2", "{}", "{}");
        assertFalse(store.beginApplying("r2", NOW.plusSeconds(3900)));
    }

    @Test void durableNotificationReservationPreventsResendAndThrottlesOtherSuggestions() {
        claim("r1", NOW); store.complete("r1", "HELD", "advice", NOW);
        assertTrue(store.claimNotification("r1", NOW, NOW.plusSeconds(86400)));
        assertFalse(store.claimNotification("r1", NOW.plusSeconds(1), NOW.plusSeconds(86400)));
        store.notification("r1", "UNKNOWN");
        store.notification("r1", "SENT");
        assertEquals("UNKNOWN", store.recent("paper:BTC-USDT", 10).getFirst().notificationStatus());
        claim("r2", NOW.plusSeconds(3600)); store.complete("r2", "HELD", "new advice", NOW);
        assertFalse(store.claimNotification("r2", NOW.plusSeconds(3600), NOW.plusSeconds(86400)));
        assertEquals("THROTTLED", store.recent("paper:BTC-USDT", 10).getFirst().notificationStatus());
    }

    private boolean claim(String id, Instant now) {
        return store.claim("paper:BTC-USDT", id, now, now.plusSeconds(3600), now.plusSeconds(300));
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:ai_review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); return source;
        }
        @Bean SqlSessionFactory factory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/trading/TradingAiReviewMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<TradingAiReviewMapper> mapper(SqlSessionFactory factory) {
            MapperFactoryBean<TradingAiReviewMapper> bean = new MapperFactoryBean<>(TradingAiReviewMapper.class);
            bean.setSqlSessionFactory(factory); return bean;
        }
        @Bean TradingAiReviewStore store(TradingAiReviewMapper mapper) { return new MyBatisTradingAiReviewStore(mapper); }
        @Bean PlatformTransactionManager transactions(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
    }
}
