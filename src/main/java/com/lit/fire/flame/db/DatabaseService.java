package com.lit.fire.flame.db;

import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.mapper.ColumnMapper;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class DatabaseService implements AutoCloseable {

    private static final int BATCH_SIZE = 500;

    private final Connection connection;
    private final String tableName;
    private final ColumnMapper mapper;

    public DatabaseService(String url, String user, String password,
                           String tableName, ColumnMapper mapper) throws SQLException {
        this.tableName = tableName;
        this.mapper = mapper;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /**
     * Creates the movies table if it does not already exist.
     * The schema starts with just the two PK columns; everything else is added dynamically.
     */
    public void ensureTableExists() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + q(tableName) + " (" +
            "\"movie_name\" TEXT NOT NULL, " +
            "\"year\"       TEXT NOT NULL, " +
            "PRIMARY KEY (\"movie_name\", \"year\")" +
            ")";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        connection.commit();
    }

    /**
     * Returns a map of column_name → pg_data_type for all columns currently in the table.
     */
    public Map<String, String> getExistingColumns() throws SQLException {
        Map<String, String> cols = new LinkedHashMap<>();
        String sql = "SELECT column_name, data_type " +
            "FROM information_schema.columns " +
            "WHERE table_name = ? AND table_schema = 'public' " +
            "ORDER BY ordinal_position";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.put(rs.getString("column_name"), rs.getString("data_type"));
                }
            }
        }
        return cols;
    }

    /**
     * Adds a single column to the table.
     * NUMERIC columns default to 0; TEXT columns default to NULL.
     */
    public void addColumn(String columnName, ColumnMapper.ColumnType type) throws SQLException {
        String colDef = type == ColumnMapper.ColumnType.NUMERIC
            ? "NUMERIC DEFAULT 0"
            : "TEXT DEFAULT NULL";
        String sql = "ALTER TABLE " + q(tableName) +
            " ADD COLUMN IF NOT EXISTS " + q(columnName) + " " + colDef;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        connection.commit();
        System.out.printf("  Added column %-30s [%s]%n", q(columnName), colDef);
    }

    /**
     * Batch-upserts all rows from the CSV into the table.
     *
     * @param csvData       parsed CSV content
     * @param csvToDb       map of CSV header → DB column name
     * @param dbColumnTypes map of DB column name → PostgreSQL data type (from information_schema)
     */
    public void batchUpsert(CsvData csvData,
                            Map<String, String> csvToDb,
                            Map<String, String> dbColumnTypes) throws SQLException {

        // Non-PK DB columns that appear in this CSV, preserving CSV order
        List<String> dataCols = csvData.headers().stream()
            .map(csvToDb::get)
            .filter(Objects::nonNull)
            .filter(col -> !mapper.isPkColumn(col))
            .distinct()
            .collect(Collectors.toList());

        List<String> allCols = new ArrayList<>();
        allCols.add(ColumnMapper.MOVIE_NAME_COL);
        allCols.add(ColumnMapper.YEAR_COL);
        allCols.addAll(dataCols);

        String colList     = allCols.stream().map(this::q).collect(Collectors.joining(", "));
        String placeholders = allCols.stream().map(c -> "?").collect(Collectors.joining(", "));

        String upsertSql;
        if (dataCols.isEmpty()) {
            upsertSql = "INSERT INTO " + q(tableName) +
                " (" + colList + ") VALUES (" + placeholders + ")" +
                " ON CONFLICT (\"movie_name\", \"year\") DO NOTHING";
        } else {
            String updateClause = dataCols.stream()
                .map(col -> q(col) + " = EXCLUDED." + q(col))
                .collect(Collectors.joining(", "));
            upsertSql = "INSERT INTO " + q(tableName) +
                " (" + colList + ") VALUES (" + placeholders + ")" +
                " ON CONFLICT (\"movie_name\", \"year\") DO UPDATE SET " + updateClause;
        }

        // Reverse lookup: dbCol → csvHeader (first match wins for deduplication safety)
        Map<String, String> dbToCsv = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : csvToDb.entrySet()) {
            dbToCsv.putIfAbsent(e.getValue(), e.getKey());
        }

        long processed = 0, skipped = 0;

        try (PreparedStatement ps = connection.prepareStatement(upsertSql)) {
            int batchCount = 0;

            for (Map<String, String> row : csvData.rows()) {
                String movieName = extractValue(row, dbToCsv, ColumnMapper.MOVIE_NAME_COL, "text");
                String year      = extractValue(row, dbToCsv, ColumnMapper.YEAR_COL, "text");

                if (movieName == null || year == null) {
                    skipped++;
                    continue;
                }

                ps.setString(1, movieName);
                ps.setString(2, year);

                for (int i = 0; i < dataCols.size(); i++) {
                    String dbCol    = dataCols.get(i);
                    String pgType   = dbColumnTypes.getOrDefault(dbCol, "text");
                    String rawValue = row.get(dbToCsv.get(dbCol));
                    String sanitized = mapper.sanitizeValue(rawValue, pgType);

                    if (sanitized == null) {
                        ps.setObject(i + 3, null);
                    } else if (mapper.isNumericType(pgType)) {
                        try {
                            ps.setBigDecimal(i + 3, new BigDecimal(sanitized));
                        } catch (NumberFormatException e) {
                            ps.setObject(i + 3, null);
                        }
                    } else {
                        ps.setString(i + 3, sanitized);
                    }
                }

                ps.addBatch();
                batchCount++;
                processed++;

                if (batchCount >= BATCH_SIZE) {
                    ps.executeBatch();
                    connection.commit();
                    batchCount = 0;
                    System.out.printf("  Processed %,d rows...%n", processed);
                }
            }

            if (batchCount > 0) {
                ps.executeBatch();
                connection.commit();
            }
        }

        System.out.printf("Import complete — upserted: %,d | skipped (missing PK): %,d%n",
            processed, skipped);
    }

    // ---- private helpers ----

    private String extractValue(Map<String, String> row, Map<String, String> dbToCsv,
                                String dbCol, String pgType) {
        String csvHeader = dbToCsv.get(dbCol);
        if (csvHeader == null) return null;
        String sanitized = mapper.sanitizeValue(row.get(csvHeader), pgType);
        return (sanitized == null || sanitized.isBlank()) ? null : sanitized;
    }

    /** Double-quotes an identifier, escaping any embedded double-quotes. */
    private String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
