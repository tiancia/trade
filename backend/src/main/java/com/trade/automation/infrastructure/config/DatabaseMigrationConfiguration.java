package com.trade.automation.infrastructure.config;

import com.trade.automation.infrastructure.migration.DatabaseMigrationStrategy;
import com.trade.automation.infrastructure.migration.V2__TradingRuntimeSafety;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Flyway is the only runtime schema writer; the release script backs up before startup. */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConfiguration {
    @Bean FlywayMigrationStrategy databaseMigrationStrategy() { return new DatabaseMigrationStrategy(); }
    @Bean JavaMigration tradingRuntimeSafetyMigration() { return new V2__TradingRuntimeSafety(); }
    @Bean FlywayConfigurationCustomizer migrationSafetyDefaults() {
        return configuration -> configuration.baselineOnMigrate(false).baselineVersion("0")
                .locations("classpath:db/migration").table("flyway_schema_history")
                .cleanDisabled(true).validateOnMigrate(true).validateMigrationNaming(true)
                .outOfOrder(false).ignoreMigrationPatterns(new String[0]);
    }
}
