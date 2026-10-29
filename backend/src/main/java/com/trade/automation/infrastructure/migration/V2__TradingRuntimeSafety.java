package com.trade.automation.infrastructure.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Frozen V2, including guarded adoption of an already applied manual trading upgrade. */
public final class V2__TradingRuntimeSafety extends BaseJavaMigration {
    public static final String RESOURCE = "db/migration-support/V2__trading_runtime_safety.sql";
    @Override public Integer getChecksum() {
        CRC32 checksum = new CRC32();
        checksum.update(LegacySchemaVerifier.read(RESOURCE).replace("\r\n", "\n").replace('\r', '\n')
                .getBytes(StandardCharsets.UTF_8));
        return (int) checksum.getValue();
    }
    @Override public boolean canExecuteInTransaction() { return false; }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String sql = LegacySchemaVerifier.read(RESOURCE);
        List<String> statements = Arrays.stream(sql.replaceAll("(?m)^\\s*--[^\\n]*", "").split(";"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (statements.size() != 4) throw new IllegalStateException("Frozen V2 migration statements changed unexpectedly");
        LegacySchemaVerifier.verifyExistingDefinitions(connection, statements.getFirst() + ";");
        verifySellCheckpoints(connection);
        try (var statement = connection.createStatement()) {
            statement.execute(statements.getFirst());
            if (!hasColumn(connection, "open_trade_realized_pnl")) statement.execute(statements.get(1));
            if (!hasColumn(connection, "closed_trade_accounting_version")) statement.execute(statements.get(2));
            // Recomputing this checkpoint is deterministic, including a legacy
            // manual upgrade. No order identity or monetary position is replaced.
            statement.executeUpdate(statements.get(3));
        }
    }

    private static boolean hasColumn(Connection connection, String name) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), "okx_risk_state", name)) {
            if (!columns.next()) return false;
            if (name.equals("open_trade_realized_pnl") && (columns.getInt("DATA_TYPE") != java.sql.Types.DECIMAL
                    && columns.getInt("DATA_TYPE") != java.sql.Types.NUMERIC
                    || columns.getInt("COLUMN_SIZE") != 38 || columns.getInt("DECIMAL_DIGITS") != 18
                    || columns.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNullable)) {
                throw new IllegalStateException("Existing trading PnL column has incompatible precision");
            }
            if (name.equals("closed_trade_accounting_version") && (columns.getInt("DATA_TYPE") != java.sql.Types.INTEGER
                    || columns.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls
                    || !"0".equals(columns.getString("COLUMN_DEF")))) {
                throw new IllegalStateException("Existing trading accounting version has incompatible type");
            }
            return true;
        }
    }

    private static void verifySellCheckpoints(Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM okx_order_fill_ledger l
                LEFT JOIN okx_orders o ON o.id = l.order_id
                WHERE l.side = 'sell' AND l.cumulative_filled_size > 0 AND (
                    o.id IS NULL OR l.average_fill_price IS NULL OR l.average_fill_price <= 0
                    OR LOCATE('-', o.inst_id) <= 1
                    OR (COALESCE(l.fee, 0) <> 0 AND (l.fee_ccy IS NULL OR l.fee_ccy NOT IN (
                        SUBSTRING(o.inst_id, 1, LOCATE('-', o.inst_id) - 1),
                        SUBSTRING(o.inst_id, LOCATE('-', o.inst_id) + 1)))))
                """;
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next() || result.getLong(1) != 0) {
                throw new IllegalStateException("Trading migration requires valid SELL checkpoints and supported base/quote fees; reconcile legacy data first");
            }
        }
    }
}
