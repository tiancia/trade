package com.trade.automation.infrastructure.migration;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Recognizes the supported pre-Flyway schema before adopting an existing database. */
public final class LegacySchemaVerifier {
    public static final String INITIAL_SCHEMA = "db/migration/V1__initial_schema.sql";
    private static final Pattern TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?(\\w+)`?\\s*\\((.*?)\\)\\s*ENGINE\\s*=\\s*InnoDB[^;]*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*`?(\\w+)`?\\s+(bigint|integer|int|tinyint|varchar|char|decimal|numeric|longtext|mediumtext|text|json|datetime|timestamp|boolean|bool|date|double|float)(?:\\((\\d+)(?:,\\s*(\\d+))?\\))?([^\\n]*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern UNIQUE = Pattern.compile(
            "UNIQUE\\s+(?:(?:KEY|INDEX)\\s+)?(?:`?\\w+`?\\s*)?\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private LegacySchemaVerifier() {}

    public static String read(String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Missing immutable migration resource: " + resource, e); }
    }

    public static Set<String> tables(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "%", null)) {
            while (rows.next()) {
                if (List.of("TABLE", "BASE TABLE").contains(rows.getString("TABLE_TYPE"))) {
                    tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }

    public static void verify(Connection connection) throws SQLException {
        Map<String, Table> baseline = definitions(read(INITIAL_SCHEMA));
        Set<String> existing = tables(connection);
        Set<String> recognized = new HashSet<>(baseline.keySet());
        recognized.add("okx_strategy_selection"); // The one supported manual V2 upgrade.
        for (String table : existing) {
            if (!recognized.contains(table)) fail(table, "unrecognized table; automatic baseline refused");
        }
        for (Table table : baseline.values()) {
            if (existing.contains(table.name())) verifyTable(connection, table);
        }
    }

    public static void verifyExistingDefinitions(Connection connection, String sql) throws SQLException {
        Set<String> existing = tables(connection);
        for (Table table : definitions(sql).values()) {
            if (existing.contains(table.name())) verifyTable(connection, table);
        }
    }

    private static Map<String, Table> definitions(String sql) {
        Map<String, Table> result = new LinkedHashMap<>();
        var tables = TABLE.matcher(sql);
        while (tables.find()) {
            String name = tables.group(1).toLowerCase(Locale.ROOT), body = tables.group(2);
            Map<String, Column> columns = new LinkedHashMap<>();
            Set<List<String>> unique = new HashSet<>();
            var matches = COLUMN.matcher(body);
            while (matches.find()) {
                String column = matches.group(1).toLowerCase(Locale.ROOT);
                columns.put(column, new Column(matches.group(2).toLowerCase(Locale.ROOT),
                        matches.group(3) == null ? 0 : Integer.parseInt(matches.group(3)),
                        matches.group(4) == null ? 0 : Integer.parseInt(matches.group(4)),
                        matches.group(5).toUpperCase(Locale.ROOT).contains("NOT NULL")));
                if (matches.group(5).toUpperCase(Locale.ROOT).contains("UNIQUE")) unique.add(List.of(column));
            }
            var constraints = UNIQUE.matcher(body);
            while (constraints.find()) unique.add(Arrays.stream(constraints.group(1).split(","))
                    .map(String::trim).map(value -> value.replace("`", "").toLowerCase(Locale.ROOT)).toList());
            if (!columns.containsKey("id")) fail(name, "missing baseline primary key definition");
            result.put(name, new Table(name, columns, unique));
        }
        if (result.isEmpty()) throw new IllegalStateException("No tables in the immutable schema definition");
        return result;
    }

    private static void verifyTable(Connection connection, Table expected) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> found = new HashSet<>();
        try (var columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(), expected.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                Column wanted = expected.columns().get(name);
                if (wanted == null) continue;
                found.add(name);
                String actual = family(columns.getString("TYPE_NAME"));
                // H2's MySQL mode represents TEXT as an unbounded VARCHAR.
                if (connection.getMetaData().getDatabaseProductName().equals("H2")
                        && family(wanted.type()).equals("text") && actual.equals("varchar")
                        && columns.getInt("COLUMN_SIZE") >= 65535) actual = "text";
                if (!actual.equals(family(wanted.type()))) fail(expected.name() + "." + name, "incompatible column type");
                if ((actual.equals("varchar") || actual.equals("char")) && columns.getInt("COLUMN_SIZE") < wanted.size()) {
                    fail(expected.name() + "." + name, "column is shorter than the supported baseline");
                }
                if (actual.equals("decimal") && (columns.getInt("DECIMAL_DIGITS") < wanted.scale()
                        || columns.getInt("COLUMN_SIZE") - columns.getInt("DECIMAL_DIGITS") < wanted.size() - wanted.scale())) {
                    fail(expected.name() + "." + name, "incompatible decimal precision");
                }
                if (wanted.required() && columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls) {
                    fail(expected.name() + "." + name, "required column is nullable");
                }
                if (name.equals("id") && !"YES".equals(columns.getString("IS_AUTOINCREMENT"))) {
                    fail(expected.name(), "id is not generated");
                }
            }
        }
        if (!found.containsAll(expected.columns().keySet())) fail(expected.name(), "missing baseline columns; legacy upgrade prerequisites are required");
        List<String> primary = new ArrayList<>();
        try (var keys = metadata.getPrimaryKeys(connection.getCatalog(), connection.getSchema(), expected.name())) {
            while (keys.next()) primary.add(keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!primary.equals(List.of("id"))) fail(expected.name(), "unsupported legacy primary key");
        Map<String, SortedMap<Integer, String>> indexes = new HashMap<>();
        try (var keys = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(), expected.name(), true, false)) {
            while (keys.next()) {
                String column = keys.getString("COLUMN_NAME");
                if (column != null) indexes.computeIfAbsent(keys.getString("INDEX_NAME"), ignored -> new TreeMap<>())
                        .put(keys.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        Set<List<String>> unique = new HashSet<>();
        indexes.values().forEach(value -> unique.add(List.copyOf(value.values())));
        if (!unique.containsAll(expected.unique())) fail(expected.name(), "missing business uniqueness constraints");
    }

    private static String family(String raw) {
        String type = raw.toLowerCase(Locale.ROOT).replace(" unsigned", "");
        return switch (type) {
            case "integer" -> "int";
            case "character varying" -> "varchar";
            case "character" -> "char";
            case "numeric" -> "decimal";
            case "datetime" -> "timestamp";
            case "mediumtext", "longtext", "character large object" -> "text";
            case "bool", "boolean", "bit" -> "tinyint";
            default -> type;
        };
    }
    private static void fail(String object, String reason) {
        throw new IllegalStateException("Database baseline rejected: " + object + ": " + reason);
    }
    private record Column(String type, int size, int scale, boolean required) {}
    private record Table(String name, Map<String, Column> columns, Set<List<String>> unique) {}
}
