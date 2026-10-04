package com.trade.architecture;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the configured schema resources without starting tasks or connecting to MySQL. */
class DatabaseSchemaInitializationTest {
    @Test
    void configuredSchemasCreateAllTablesAndPreserveExistingStateOnRestart() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.sql.init", Bindable.of(SqlInitializationProperties.class)).get();
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:module_schemas_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");

        try (var connection = dataSource.getConnection()) {
            var initializer = new ApplicationDataSourceScriptDatabaseInitializer(dataSource, properties);
            initializer.setResourceLoader(new H2SchemaResourceLoader());
            assertTrue(initializer.initializeDatabase());
            var jdbc = new JdbcTemplate(dataSource);
            assertEquals(46, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """, Integer.class));
            assertGeneratedPrimaryKeys(connection, jdbc);
            assertEquals("HALTED", jdbc.queryForObject(
                    "SELECT status FROM okx_fund_safety_state WHERE account_scope = 'live'", String.class));
            assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM marketplace_categories", Integer.class));

            jdbc.update("UPDATE okx_fund_safety_state SET reason = ? WHERE account_scope = 'live'",
                    "Reviewed by operator");
            assertTrue(initializer.initializeDatabase());
            assertEquals("Reviewed by operator", jdbc.queryForObject(
                    "SELECT reason FROM okx_fund_safety_state WHERE account_scope = 'live'", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM okx_fund_safety_state", Integer.class));
            assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM marketplace_categories", Integer.class));
        }
    }

    private static void assertGeneratedPrimaryKeys(Connection connection, JdbcTemplate jdbc) throws Exception {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name
                """, String.class);
        for (String table : tables) {
            var primaryKey = new ArrayList<String>();
            try (var keys = connection.getMetaData().getPrimaryKeys(null, "public", table)) {
                while (keys.next()) primaryKey.add(keys.getString("COLUMN_NAME"));
            }
            assertEquals(List.of("id"), primaryKey, table + " primary key");
            try (var columns = connection.getMetaData().getColumns(null, "public", table, "id")) {
                assertTrue(columns.next(), table + " id column");
                assertEquals(Types.BIGINT, columns.getInt("DATA_TYPE"), table + " id type");
                assertEquals("YES", columns.getString("IS_AUTOINCREMENT"), table + " generated id");
            }
        }
    }

    private static final class H2SchemaResourceLoader extends DefaultResourceLoader {
        @Override
        public Resource getResource(String location) {
            Resource original = super.getResource(location);
            if (!location.startsWith("classpath:db/schema/")) return original;
            try (var input = original.getInputStream()) {
                // H2 executes the production DDL/seed; omit only MySQL-specific table options.
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4"
                                + "\\s+COLLATE=utf8mb4_unicode_ci(?:\\s+COMMENT='[^']*')?", "");
                return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), location);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
