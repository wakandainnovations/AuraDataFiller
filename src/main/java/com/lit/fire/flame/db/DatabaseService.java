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
     * Renames the legacy 'year' PK column to 'release_date' if it still exists.
     * Safe to call on a brand-new table (no rows in information_schema → no-op).
     */
    public void migrateLegacyYearColumn() throws SQLException {
        String checkSql =
            "SELECT 1 FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'year'";
        boolean yearExists;
        try (PreparedStatement ps = connection.prepareStatement(checkSql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                yearExists = rs.next();
            }
        }
        if (yearExists) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE " + q(tableName) +
                    " RENAME COLUMN \"year\" TO \"release_date\"");
            }
            connection.commit();
            System.out.println("  Migrated column 'year' → 'release_date'");
        }
    }

    /**
     * Creates the movies table if it does not already exist.
     * Migrates the legacy 'year' column to 'release_date' first when needed.
     * The schema starts with just the two PK columns; everything else is added dynamically.
     */
    public void ensureTableExists() throws SQLException {
        migrateLegacyYearColumn();

        String sql = "CREATE TABLE IF NOT EXISTS " + q(tableName) + " (" +
            "\"movie_name\"   TEXT NOT NULL, " +
            "\"release_date\" TEXT NOT NULL, " +
            "PRIMARY KEY (\"movie_name\", \"release_date\")" +
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
     * Creates a GiST trigram index on movie_name for fast similarity searches.
     * Requires pg_trgm extension (enabled separately).
     */
    public void ensureFuzzyIndex() throws SQLException {
        String idxName = "idx_" + tableName.replaceAll("[^a-z0-9]", "_") + "_trgm";
        String sql = "CREATE INDEX IF NOT EXISTS " + idxName +
            " ON " + q(tableName) + " USING GiST (movie_name gist_trgm_ops)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        connection.commit();
    }

    /**
     * Checks all incoming (movie_name, release_date) pairs against the existing table for fuzzy duplicates.
     *
     * Two match tiers are applied:
     *   1. Case-insensitive exact match → always auto-merged (different capitalisation, same title).
     *   2. Trigram similarity ≥ warnThreshold with length-ratio ≥ 0.70 to suppress short-name
     *      false positives like "Ram" vs "Ram Ram":
     *        • sim ≥ autoMergeThreshold → auto-merge, printed to stdout
     *        • sim ≥ warnThreshold      → added to warnOutput for human review
     *
     * @param nameDatePairs      unique [movie_name, release_date] pairs from the incoming CSV
     * @param warnThreshold      minimum similarity to flag as potential duplicate
     * @param autoMergeThreshold minimum similarity to automatically redirect to canonical name
     * @param warnOutput         list populated with human-readable warning strings
     * @return map of "lower(movie_name)|release_date" → canonical movie_name in DB
     */
    public Map<String, String> findFuzzyMatches(
        List<String[]> nameDatePairs,
        double warnThreshold,
        double autoMergeThreshold,
        List<String> warnOutput
    ) throws SQLException {
        Map<String, String> autoMergeMap = new LinkedHashMap<>();
        if (nameDatePairs.isEmpty()) return autoMergeMap;

        // Populate session-scoped temp table with incoming pairs
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TEMP TABLE IF NOT EXISTS _tmp_incoming_movies " +
                "(movie_name TEXT, release_date TEXT)");
            stmt.execute("TRUNCATE _tmp_incoming_movies");
        }
        try (PreparedStatement ps =
                 connection.prepareStatement("INSERT INTO _tmp_incoming_movies VALUES (?, ?)")) {
            int count = 0;
            for (String[] pair : nameDatePairs) {
                ps.setString(1, pair[0]);
                ps.setString(2, pair[1]);
                ps.addBatch();
                if (++count % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }

        // Tier 1: case-insensitive exact matches (different formatting, same title)
        String q1 = "SELECT i.movie_name AS incoming, i.release_date, e.movie_name AS canonical " +
            "FROM _tmp_incoming_movies i " +
            "JOIN " + q(tableName) + " e ON i.release_date = e.release_date " +
            "WHERE lower(trim(i.movie_name)) = lower(trim(e.movie_name)) " +
            "  AND trim(i.movie_name) != trim(e.movie_name)";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(q1)) {
            while (rs.next()) {
                String incoming     = rs.getString("incoming");
                String releaseDate  = rs.getString("release_date");
                String canonical    = rs.getString("canonical");
                autoMergeMap.put(mergeKey(incoming, releaseDate), canonical);
                System.out.printf("  [AUTO-MERGE case  ] %-45s (%s)  →  %s%n",
                    "'" + incoming + "'", releaseDate, "'" + canonical + "'");
            }
        }

        // Tier 2: trigram fuzzy matches (excluding case-insensitive exact matches already handled)
        //   Length-ratio filter: LEAST/GREATEST >= 0.70 drops "Ram" vs "Ram Ram" (ratio 0.43)
        //   but keeps "Panakkaran" vs "Pannakkaran" (ratio 0.96).
        String q2 =
            "SELECT DISTINCT ON (i.movie_name, i.release_date) " +
            "  i.movie_name AS incoming, i.release_date, e.movie_name AS canonical, " +
            "  ROUND(similarity(i.movie_name, e.movie_name)::numeric, 3) AS sim " +
            "FROM _tmp_incoming_movies i " +
            "JOIN " + q(tableName) + " e ON i.release_date = e.release_date " +
            "WHERE similarity(i.movie_name, e.movie_name) >= ? " +
            "  AND lower(trim(i.movie_name)) != lower(trim(e.movie_name)) " +
            "  AND LEAST(length(trim(i.movie_name)), length(trim(e.movie_name)))::float " +
            "    / GREATEST(length(trim(i.movie_name)), length(trim(e.movie_name)), 1) >= 0.70 " +
            "ORDER BY i.movie_name, i.release_date, similarity(i.movie_name, e.movie_name) DESC";

        try (PreparedStatement ps = connection.prepareStatement(q2)) {
            ps.setDouble(1, warnThreshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String incoming    = rs.getString("incoming");
                    String releaseDate = rs.getString("release_date");
                    String canonical   = rs.getString("canonical");
                    double sim         = rs.getDouble("sim");

                    if (sim >= autoMergeThreshold) {
                        autoMergeMap.put(mergeKey(incoming, releaseDate), canonical);
                        System.out.printf("  [AUTO-MERGE fuzzy %.3f] %-45s (%s)  →  %s%n",
                            sim, "'" + incoming + "'", releaseDate, "'" + canonical + "'");
                    } else {
                        warnOutput.add(String.format(
                            "  [WARN  %.3f] '%-45s (%s)  ≈  '%s'",
                            sim, incoming + "'", releaseDate, canonical));
                    }
                }
            }
        }

        return autoMergeMap;
    }

    /**
     * Batch-upserts all rows from the CSV into the table.
     * autoMergeMap normalises fuzzy-matched incoming movie names to their canonical DB names
     * before the conflict check, so typo variants land on the same row as the clean name.
     *
     * @param csvData       parsed CSV content
     * @param csvToDb       map of CSV header → DB column name
     * @param dbColumnTypes map of DB column name → PostgreSQL data type (from information_schema)
     * @param autoMergeMap  map of "lower(movie_name)|release_date" → canonical movie_name (may be empty)
     */
    public void batchUpsert(CsvData csvData,
                            Map<String, String> csvToDb,
                            Map<String, String> dbColumnTypes,
                            Map<String, String> autoMergeMap) throws SQLException {

        List<String> dataCols = csvData.headers().stream()
            .map(csvToDb::get)
            .filter(Objects::nonNull)
            .filter(col -> !mapper.isPkColumn(col))
            .distinct()
            .collect(Collectors.toList());

        List<String> allCols = new ArrayList<>();
        allCols.add(ColumnMapper.MOVIE_NAME_COL);
        allCols.add(ColumnMapper.RELEASE_DATE_COL);
        allCols.addAll(dataCols);

        String colList      = allCols.stream().map(this::q).collect(Collectors.joining(", "));
        String placeholders = allCols.stream().map(c -> "?").collect(Collectors.joining(", "));

        String upsertSql;
        if (dataCols.isEmpty()) {
            upsertSql = "INSERT INTO " + q(tableName) +
                " (" + colList + ") VALUES (" + placeholders + ")" +
                " ON CONFLICT (\"movie_name\", \"release_date\") DO NOTHING";
        } else {
            String updateClause = dataCols.stream()
                .map(col -> q(col) + " = EXCLUDED." + q(col))
                .collect(Collectors.joining(", "));
            upsertSql = "INSERT INTO " + q(tableName) +
                " (" + colList + ") VALUES (" + placeholders + ")" +
                " ON CONFLICT (\"movie_name\", \"release_date\") DO UPDATE SET " + updateClause;
        }

        Map<String, String> dbToCsv = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : csvToDb.entrySet()) {
            dbToCsv.putIfAbsent(e.getValue(), e.getKey());
        }

        long processed = 0, skipped = 0, merged = 0;

        try (PreparedStatement ps = connection.prepareStatement(upsertSql)) {
            int batchCount = 0;

            for (Map<String, String> row : csvData.rows()) {
                String movieName  = extractValue(row, dbToCsv, ColumnMapper.MOVIE_NAME_COL, "text");
                String releaseDate = extractValue(row, dbToCsv, ColumnMapper.RELEASE_DATE_COL, "text");

                if (movieName == null || releaseDate == null) {
                    skipped++;
                    continue;
                }

                // Apply fuzzy name normalisation before the conflict check
                String normalised = autoMergeMap.get(mergeKey(movieName, releaseDate));
                if (normalised != null) {
                    movieName = normalised;
                    merged++;
                }

                ps.setString(1, movieName);
                ps.setString(2, releaseDate);

                for (int i = 0; i < dataCols.size(); i++) {
                    String dbCol     = dataCols.get(i);
                    String pgType    = dbColumnTypes.getOrDefault(dbCol, "text");
                    String rawValue  = row.get(dbToCsv.get(dbCol));
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

        System.out.printf(
            "Import complete — upserted: %,d | fuzzy-merged: %,d | skipped (missing PK): %,d%n",
            processed, merged, skipped);
    }

    // ---- private helpers ----

    /** Lookup key for the autoMergeMap: normalised name + release_date. */
    private static String mergeKey(String movieName, String releaseDate) {
        return movieName.toLowerCase().trim() + "|" + releaseDate.trim();
    }

    private String extractValue(Map<String, String> row, Map<String, String> dbToCsv,
                                String dbCol, String pgType) {
        String csvHeader = dbToCsv.get(dbCol);
        if (csvHeader == null) return null;
        String sanitized = mapper.sanitizeValue(row.get(csvHeader), pgType);
        return (sanitized == null || sanitized.isBlank()) ? null : sanitized;
    }

    /** Double-quotes a SQL identifier, escaping any embedded double-quotes. */
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
