package com.trade.trading.persistence;

import com.trade.trading.application.TradingLeaderLease;
import com.trade.trading.application.port.TradingLeaderLeaseRepository;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(MyBatisTradingLeaderLeaseRepositoryTest.PersistenceTestConfiguration.class)
@Sql(statements = {
        "DROP TABLE IF EXISTS okx_trading_leader_lease",
        "CREATE TABLE okx_trading_leader_lease (" +
                "lease_name VARCHAR(128) PRIMARY KEY, owner_id VARCHAR(128) NOT NULL," +
                "lease_until TIMESTAMP NOT NULL, fencing_token BIGINT NOT NULL," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)"
})
class MyBatisTradingLeaderLeaseRepositoryTest {
    @Autowired
    private TradingLeaderLeaseRepository repository;

    @Test
    void leaseIsSingleOwnerAndFencingTokenAdvancesOnHandoff() {
        TradingLeaderLease first = repository.acquireOrRenew(
                "okx-account",
                "instance-a",
                Duration.ofSeconds(30)
        );
        TradingLeaderLease blocked = repository.acquireOrRenew(
                "okx-account",
                "instance-b",
                Duration.ofSeconds(30)
        );

        assertEquals("instance-a", first.ownerId());
        assertEquals("instance-a", blocked.ownerId());
        assertEquals(1L, blocked.fencingToken());
        assertTrue(repository.release("okx-account", "instance-a"));

        TradingLeaderLease handedOff = repository.acquireOrRenew(
                "okx-account",
                "instance-b",
                Duration.ofSeconds(30)
        );
        TradingLeaderLease renewed = repository.acquireOrRenew(
                "okx-account",
                "instance-b",
                Duration.ofSeconds(30)
        );

        assertEquals("instance-b", handedOff.ownerId());
        assertEquals(2L, handedOff.fencingToken());
        assertEquals(2L, renewed.fencingToken());
        assertTrue(renewed.leaseUntil().isAfter(handedOff.updatedAt()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceTestConfiguration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL(
                    "jdbc:h2:mem:trading_leader_lease;MODE=MySQL;"
                            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
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
                    new ClassPathResource("mapper/trading/TradingLeaderLeaseMapper.xml")
            );
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<TradingLeaderLeaseMapper> tradingLeaderLeaseMapper(
                SqlSessionFactory sqlSessionFactory
        ) {
            MapperFactoryBean<TradingLeaderLeaseMapper> factory =
                    new MapperFactoryBean<>(TradingLeaderLeaseMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        TradingLeaderLeaseRepository tradingLeaderLeaseRepository(
                TradingLeaderLeaseMapper mapper
        ) {
            return new MyBatisTradingLeaderLeaseRepository(mapper);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
