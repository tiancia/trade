package com.trade.trading.config;

import com.trade.trading.persistence.AiDecisionAuditMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
@Conditional(AiTradeMyBatisConfiguration.DatabaseConfiguredCondition.class)
@MapperScan(
        basePackageClasses = AiDecisionAuditMapper.class,
        annotationClass = Mapper.class,
        sqlSessionFactoryRef = "aiTradeSqlSessionFactory"
)
public class AiTradeMyBatisConfiguration {

    @Bean
    public DataSource aiTradeDataSource(AiTradeDatabaseProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        return dataSource;
    }

    @Bean
    public SqlSessionFactory aiTradeSqlSessionFactory(
            @Qualifier("aiTradeDataSource") DataSource dataSource,
            ApplicationContext applicationContext
    ) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(applicationContext.getResources("classpath*:mapper/**/*.xml"));
        return factoryBean.getObject();
    }

    static class DatabaseConfiguredCondition implements Condition, EnvironmentAware {
        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = environment == null ? context.getEnvironment() : environment;
            boolean enabled = env.getProperty("trade.ai.database.enabled", Boolean.class, true);
            String jdbcUrl = env.getProperty("trade.ai.database.jdbc-url");
            return enabled && jdbcUrl != null && !jdbcUrl.isBlank();
        }
    }
}
