package com.trade.polymarket.infrastructure.persistence;

import com.trade.polymarket.application.port.PolymarketExecutionGuard;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(MyBatisPolymarketExecutionGuardTest.PersistenceTestConfiguration.class)
@Sql(statements = {
        "DROP TABLE IF EXISTS polymarket_execution_guard",
        "CREATE TABLE polymarket_execution_guard (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "guard_name VARCHAR(64) NOT NULL UNIQUE, pending_decision_id BIGINT,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
})
class MyBatisPolymarketExecutionGuardTest {
    @Autowired
    private PolymarketExecutionGuard guard;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void reservationSurvivesAdapterRestartAndOnlyItsOwnerCanRelease() {
        assertTrue(guard.reserve(11L));
        Long rowId = jdbc.queryForObject("SELECT id FROM polymarket_execution_guard WHERE guard_name = 'primary'", Long.class);
        assertTrue(rowId > 0);
        assertFalse(guard.reserve(11L), "replaying a reserved decision must not authorize another external submission");
        try (var restarted = new AnnotationConfigApplicationContext(PersistenceTestConfiguration.class)) {
            PolymarketExecutionGuard rebuiltGuard = restarted.getBean(PolymarketExecutionGuard.class);
            assertFalse(rebuiltGuard.reserve(12L));
            rebuiltGuard.release(12L);
            assertEquals(11L, pendingDecision());
            assertFalse(rebuiltGuard.reserve(13L));
            rebuiltGuard.release(11L);
            assertTrue(rebuiltGuard.reserve(12L));
        }
        assertEquals(12L, pendingDecision());
        assertFalse(guard.reserve(13L));
        assertEquals(rowId, jdbc.queryForObject(
                "SELECT id FROM polymarket_execution_guard WHERE guard_name = 'primary'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM polymarket_execution_guard", Integer.class));
    }

    @Test
    void reservationAndMatchingReleaseCommitIndependentlyOfAnOuterRollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertTrue(guard.reserve(21L));
            status.setRollbackOnly();
        });
        assertEquals(21L, pendingDecision());
        assertFalse(guard.reserve(22L));

        outer.executeWithoutResult(status -> {
            guard.release(21L);
            status.setRollbackOnly();
        });
        assertTrue(guard.reserve(22L));
        assertEquals(22L, pendingDecision());
    }

    @Test
    void concurrentDecisionsCannotBothReserveTheSingleAccount() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> reserveTogether(31L, ready, start));
            var second = workers.submit(() -> reserveTogether(32L, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                boolean firstWon = first.get(5, TimeUnit.SECONDS);
                boolean secondWon = second.get(5, TimeUnit.SECONDS);
                assertTrue(firstWon ^ secondWon, "exactly one database reservation must succeed");
                assertEquals(firstWon ? 31L : 32L, pendingDecision());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM polymarket_execution_guard", Integer.class));
            } finally {
                start.countDown();
            }
        }
    }

    @Test
    void unavailableGuardAndMissingAuditIdentityFailClosed() {
        assertThrows(IllegalStateException.class, () -> PolymarketExecutionGuard.UNAVAILABLE.reserve(1L));
        assertThrows(IllegalArgumentException.class, () -> guard.reserve(null));
        assertThrows(IllegalArgumentException.class, () -> guard.reserve(0L));
        assertThrows(IllegalArgumentException.class, () -> guard.release(-1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM polymarket_execution_guard", Integer.class));
    }

    private boolean reserveTogether(Long decisionId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent reserve test was not started");
        }
        return guard.reserve(decisionId);
    }

    private Long pendingDecision() {
        return jdbc.queryForObject(
                "SELECT pending_decision_id FROM polymarket_execution_guard WHERE guard_name = 'primary'", Long.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceTestConfiguration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:polymarket_execution_guard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/polymarket/PolymarketExecutionGuardMapper.xml"));
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<PolymarketExecutionGuardMapper> polymarketExecutionGuardMapper(SqlSessionFactory factory) {
            MapperFactoryBean<PolymarketExecutionGuardMapper> mapper = new MapperFactoryBean<>(PolymarketExecutionGuardMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }

        @Bean
        PolymarketExecutionGuard polymarketExecutionGuard(PolymarketExecutionGuardMapper mapper) {
            return new MyBatisPolymarketExecutionGuard(mapper);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
