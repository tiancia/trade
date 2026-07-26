package com.trade.trading.persistence;

import com.trade.trading.order.OrderFill;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.order.OrderReservation;
import com.trade.trading.order.OrderStatus;
import com.trade.trading.order.OrderSubmission;
import com.trade.trading.order.TradingOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
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

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(MyBatisTradingOrderRepositoryTest.PersistenceTestConfiguration.class)
@Sql(statements = {
        "DROP TABLE IF EXISTS okx_order_status_history",
        "DROP TABLE IF EXISTS okx_orders",
        "CREATE TABLE okx_orders (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "idempotency_key VARCHAR(64) NOT NULL UNIQUE," +
                "client_order_id VARCHAR(32) NOT NULL UNIQUE," +
                "exchange_order_id VARCHAR(128), decision_id VARCHAR(64), strategy_id VARCHAR(128)," +
                "inst_id VARCHAR(64) NOT NULL, action VARCHAR(32) NOT NULL, side VARCHAR(16) NOT NULL," +
                "td_mode VARCHAR(32), order_type VARCHAR(32), target_currency VARCHAR(32)," +
                "requested_size DECIMAL(38,18) NOT NULL, status VARCHAR(32) NOT NULL, version BIGINT NOT NULL," +
                "filled_base_amount DECIMAL(38,18), average_fill_price DECIMAL(38,18), fee DECIMAL(38,18)," +
                "fee_ccy VARCHAR(32), failure_code VARCHAR(64), failure_message VARCHAR(1000)," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL," +
                "submitted_at TIMESTAMP, completed_at TIMESTAMP)",
        "CREATE TABLE okx_order_status_history (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL," +
                "from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL, version BIGINT NOT NULL," +
                "reason VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL," +
                "UNIQUE(order_id, version))"
})
class MyBatisTradingOrderRepositoryTest {
    @Autowired
    private OrderLifecycleService lifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uniqueKeyAndOptimisticStateTransitionsArePersistedWithHistory() {
        OrderSubmission submission = new OrderSubmission(
                "database-idempotency-key",
                "stbu-database-order",
                "decision-1",
                "strategy-1",
                "BTC-USDT",
                "BUY",
                "buy",
                "cash",
                "market",
                "quote_ccy",
                BigDecimal.TEN
        );

        OrderReservation first = lifecycleService.reserve(submission);
        OrderReservation replay = lifecycleService.reserve(submission);
        lifecycleService.markAccepted(submission.idempotencyKey(), "exchange-1");
        lifecycleService.markFilled(
                submission.idempotencyKey(),
                "exchange-1",
                new OrderFill(new BigDecimal("0.001"), new BigDecimal("50000"),
                        new BigDecimal("-0.000001"), "BTC")
        );

        assertTrue(first.acquired());
        assertFalse(replay.acquired());
        assertEquals(OrderStatus.FILLED,
                lifecycleService.find(submission.idempotencyKey()).orElseThrow().getStatus());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM okx_orders", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM okx_order_status_history", Integer.class));
    }

    /** Focused MyBatis slice for the order ledger and its transaction boundary. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceTestConfiguration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL(
                    "jdbc:h2:mem:order_state;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
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
                    new ClassPathResource("mapper/trading/TradingOrderMapper.xml")
            );
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<TradingOrderMapper> tradingOrderMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<TradingOrderMapper> factory =
                    new MapperFactoryBean<>(TradingOrderMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        TradingOrderRepository tradingOrderRepository(TradingOrderMapper mapper) {
            return new MyBatisTradingOrderRepository(mapper);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        OrderLifecycleService orderLifecycleService(
                TradingOrderRepository repository,
                MeterRegistry meterRegistry
        ) {
            return new OrderLifecycleService(repository, meterRegistry);
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
