package com.trade.automation.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Adopts recognized legacy databases and migrates before any application-ready task starts. */
public final class DatabaseMigrationStrategy implements FlywayMigrationStrategy {
    private static final ConcurrentHashMap<String, ReentrantLock> TEST_LOCKS = new ConcurrentHashMap<>();

    @Override public void migrate(Flyway flyway) {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var ignored = MigrationLock.acquire(connection)) {
            var tables = LegacySchemaVerifier.tables(connection);
            if (!tables.isEmpty() && !tables.contains("flyway_schema_history")) {
                LegacySchemaVerifier.verify(connection);
                // Version zero adopts only a verified legacy layout. V1's frozen
                // CREATE IF NOT EXISTS statements then fill missing modules safely.
                flyway.baseline();
            }
            flyway.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration connection or lock failed; application startup stopped", e);
        }
    }

    private static final class MigrationLock implements AutoCloseable {
        private final Connection connection;
        private final ReentrantLock testLock;
        private final String name;
        private MigrationLock(Connection connection, ReentrantLock testLock, String name) {
            this.connection = connection; this.testLock = testLock; this.name = name;
        }
        static MigrationLock acquire(Connection connection) throws SQLException {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.equals("h2")) {
                ReentrantLock lock = TEST_LOCKS.computeIfAbsent(connection.getMetaData().getURL(), ignored -> new ReentrantLock());
                lock.lock(); return new MigrationLock(connection, lock, null);
            }
            if (!product.equals("mysql")) throw new IllegalStateException("Automatic migration supports MySQL; H2 is reserved for offline tests");
            String name = "trade-schema-" + Integer.toUnsignedString(connection.getCatalog().hashCode(), 16);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 60)")) {
                statement.setString(1, name);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Another database migration holds the schema lock");
                }
            }
            return new MigrationLock(connection, null, name);
        }
        @Override public void close() throws SQLException {
            if (testLock != null) { testLock.unlock(); return; }
            try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name); statement.execute();
            }
        }
    }
}
