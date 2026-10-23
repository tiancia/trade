package com.trade.weibo.infrastructure.persistence;

import com.trade.client.weibo.WeiboAccessToken;
import com.trade.client.weibo.WeiboApi;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.application.port.WeiboOAuthStateRepository;
import com.trade.weibo.application.service.WeiboOAuthService;
import com.trade.weibo.domain.exception.WeiboOAuthException;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises actual Spring transaction propagation and production OAuth-state SQL. */
@SpringJUnitConfig(WeiboOAuthTransactionTest.Config.class)
class WeiboOAuthTransactionTest {
    private static final Instant NOW = Instant.parse("2026-10-23T01:00:00Z");
    @Autowired private WeiboOAuthService service;
    @Autowired private WeiboOAuthStateRepository states;
    @Autowired private WeiboAccountTokenRepository tokens;
    @Autowired private WeiboApi api;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void schema() throws Exception {
        reset(api, tokens);
        jdbc.execute("DROP ALL OBJECTS");
        String sql = new ClassPathResource("db/schema/weibo/schema.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        states.save("state", NOW.plusSeconds(600));
    }

    @Test
    void failedExchangeDoesNotRestoreConsumedStateOrHoldATransaction() {
        when(api.exchangeCode("code")).thenAnswer(ignored -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertConsumed();
            throw new IllegalStateException("offline exchange failure");
        });

        assertThrows(IllegalStateException.class, () -> service.handleCallback("code", "state"));
        assertConsumed();
        assertThrows(WeiboOAuthException.class, () -> service.handleCallback("code", "state"));
        verify(api, times(1)).exchangeCode("code");
    }

    @Test
    void failedAccountLookupDoesNotAllowTheSameCallbackToExchangeAgain() {
        when(api.exchangeCode("code")).thenReturn(new WeiboAccessToken("offline-token", 3600));
        when(api.getUid("offline-token")).thenAnswer(ignored -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("offline account lookup failure");
        });

        assertThrows(IllegalStateException.class, () -> service.handleCallback("code", "state"));
        assertConsumed();
        assertThrows(WeiboOAuthException.class, () -> service.handleCallback("code", "state"));
        verify(api, times(1)).exchangeCode("code");
    }

    @Test
    void tokenStorageFailureAndCallerRollbackCannotRestoreTheState() {
        when(api.exchangeCode("code")).thenAnswer(ignored -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertConsumed();
            return new WeiboAccessToken("offline-token", 3600);
        });
        when(api.getUid("offline-token")).thenReturn("uid");
        doAnswer(ignored -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("offline token storage failure");
        }).when(tokens).upsert(any(), any(), any());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertThrows(IllegalStateException.class, () -> service.handleCallback("code", "state"));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            status.setRollbackOnly();
        });

        assertConsumed();
        assertThrows(WeiboOAuthException.class, () -> service.handleCallback("code", "state"));
        verify(api, times(1)).exchangeCode("code");
    }

    @Test
    void stateConsumptionCommitsEvenWhenItsCallerRollsBack() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(states.consume("state", NOW));
            status.setRollbackOnly();
        });

        assertConsumed();
        assertFalse(states.consume("state", NOW));
    }

    private void assertConsumed() {
        assertNotNull(jdbc.queryForObject("SELECT used_at FROM weibo_oauth_state WHERE state='state'", Timestamp.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:weibo_oauth_transaction;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/weibo/WeiboMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<WeiboMapper> mapper(SqlSessionFactory sessions) {
            MapperFactoryBean<WeiboMapper> factory = new MapperFactoryBean<>(WeiboMapper.class);
            factory.setSqlSessionFactory(sessions);
            return factory;
        }
        @Bean WeiboOAuthStateRepository states(WeiboMapper mapper) {
            return new MyBatisWeiboOAuthStateRepository(mapper);
        }
        @Bean WeiboApi api() { return mock(WeiboApi.class); }
        @Bean WeiboAccountTokenRepository tokens() { return mock(WeiboAccountTokenRepository.class); }
        @Bean WeiboOAuthService service(WeiboApi api, WeiboOAuthStateRepository states,
                WeiboAccountTokenRepository tokens) {
            return new WeiboOAuthService(api, states, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
