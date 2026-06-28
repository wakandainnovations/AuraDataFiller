package com.lit.fire.flame.crawler;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database operations for the Sacnilk box-office crawler.
 * Keeps a dedicated connection separate from the CSV-import path.
 */
public class CrawlerDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public CrawlerDatabaseService(String url, String user, String password,
                                   String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /** Returns true when the target table already exists in the public schema. */
    public boolean tableExists() throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Ensures that all columns written by the crawler exist in the table.
     * Safe to call on every startup (uses ADD COLUMN IF NOT EXISTS).
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"revenue\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"budget\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"runtime\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"genre\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"language\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"rating_10\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"status\" TEXT");
        }
        connection.commit();
    }

    /**
     * Returns all distinct (movie_name, release_year) pairs stored in the table.
     * Each element is a String[3]: {movie_name, 4-digit-year, earliest_release_date}.
     * The earliest release_date within the year is included so callers can look up
     * the historical exchange rate for that specific date.
     */
    public List<String[]> getAllMovieNameYears() throws SQLException {
        List<String[]> result = new ArrayList<>();
        String sql = "SELECT movie_name, LEFT(release_date, 4) AS yr, MIN(release_date) AS release_date " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LENGTH(release_date) >= 4 " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "ORDER BY yr, movie_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name        = rs.getString("movie_name");
                String yr          = rs.getString("yr");
                String releaseDate = rs.getString("release_date");
                if (name != null && yr != null && yr.matches("\\d{4}")) {
                    result.add(new String[]{name, yr, releaseDate});
                }
            }
        }
        return result;
    }

    /**
     * Returns distinct (movie_name, year, release_date) for entries where
     * revenue or budget is still 0 (unfilled default). Used by secondary crawlers
     * (BOM, Koimoi) to skip movies that sacnilk already enriched.
     */
    public List<String[]> getMoviesMissingBoxOffice() throws SQLException {
        List<String[]> result = new ArrayList<>();
        String sql = "SELECT movie_name, LEFT(release_date, 4) AS yr, MIN(release_date) AS release_date " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LENGTH(release_date) >= 4 " +
            "  AND (COALESCE(\"revenue\", 0) = 0 OR COALESCE(\"budget\", 0) = 0) " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "ORDER BY yr, movie_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name        = rs.getString("movie_name");
                String yr          = rs.getString("yr");
                String releaseDate = rs.getString("release_date");
                if (name != null && yr != null && yr.matches("\\d{4}")) {
                    result.add(new String[]{name, yr, releaseDate});
                }
            }
        }
        return result;
    }

    /**
     * Updates revenue and/or budget (both in full USD) for every row whose
     * movie_name matches and whose release_date starts with the given year.
     *
     * Values are already converted from INR Crore to whole USD by the caller.
     * Only non-null values are written; the other column is left unchanged via COALESCE.
     *
     * @return number of rows updated
     */
    public int updateBoxOffice(String movieName, String year,
                                Long revenueUsd, Long budgetUsd) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"revenue\" = COALESCE(?, \"revenue\"), \"budget\" = COALESCE(?, \"budget\")" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, revenueUsd);
            ps.setObject(2, budgetUsd);
            ps.setString(3, movieName);
            ps.setString(4, year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    /**
     * Updates all sacnilk-sourced fields for every row matching movieName + year.
     *
     * Update policy per column:
     *   revenue / budget  – only set when the current DB value is 0, NULL, or negative
     *                       (treats those as "missing / wrong format"); a positive value
     *                       is already valid and is left untouched
     *   runtime           – set when current value is 0 or NULL and sacnilk has a value
     *   genre             – set when current value is NULL or empty
     *   rating_10         – set when current value is 0 or NULL and sacnilk has a value
     *   status            – set when current value is NULL or empty
     *
     * NOTE: language is intentionally excluded — it is part of the PK and sacnilk returns
     * multi-valued strings (e.g. "Hindi, Tamil, Telugu") that must not overwrite it.
     *
     * @return number of rows updated
     */
    public int updateMovieDetails(String movieName, String year,
                                   Long revenueUsd, Long budgetUsd,
                                   Integer runtimeMinutes, String genre,
                                   String language, Double rating, String status) throws SQLException {
        String sql =
            "UPDATE " + q(tableName) + " SET" +
            "  \"revenue\"   = CASE WHEN ? IS NOT NULL AND COALESCE(\"revenue\", 0) <= 0" +
            "                  THEN ? ELSE \"revenue\" END," +
            "  \"budget\"    = CASE WHEN ? IS NOT NULL AND COALESCE(\"budget\", 0) <= 0" +
            "                  THEN ? ELSE \"budget\" END," +
            "  \"runtime\"   = CASE WHEN ? IS NOT NULL" +
            "                       AND COALESCE(\"runtime\", 0) = 0" +
            "                  THEN ? ELSE \"runtime\" END," +
            "  \"genre\"     = CASE WHEN ? IS NOT NULL AND ? <> ''" +
            "                       AND (\"genre\" IS NULL OR \"genre\" = '')" +
            "                  THEN ? ELSE \"genre\" END," +
            "  \"rating_10\" = CASE WHEN ? IS NOT NULL" +
            "                       AND COALESCE(\"rating_10\", 0) = 0" +
            "                  THEN ? ELSE \"rating_10\" END," +
            "  \"status\"    = CASE WHEN ? IS NOT NULL AND ? <> ''" +
            "                       AND (\"status\" IS NULL OR \"status\" = '')" +
            "                  THEN ? ELSE \"status\" END" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";

        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            // revenue (NUMERIC, appears twice: WHEN condition + THEN value)
            setLongOrNull(ps, i++, revenueUsd);
            setLongOrNull(ps, i++, revenueUsd);
            // budget (NUMERIC, appears twice: WHEN condition + THEN value)
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, budgetUsd);
            // runtime (NUMERIC, appears twice: condition + value)
            setIntOrNull(ps, i++, runtimeMinutes);
            setIntOrNull(ps, i++, runtimeMinutes);
            // genre (TEXT, appears three times: not-null, not-empty, value)
            setStringOrNull(ps, i++, genre);
            setStringOrNull(ps, i++, genre);
            setStringOrNull(ps, i++, genre);
            // rating_10 (NUMERIC, appears twice)
            setDoubleOrNull(ps, i++, rating);
            setDoubleOrNull(ps, i++, rating);
            // status (TEXT, appears three times)
            setStringOrNull(ps, i++, status);
            setStringOrNull(ps, i++, status);
            setStringOrNull(ps, i++, status);
            // WHERE
            ps.setString(i++, movieName);
            ps.setString(i,   year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    private void setLongOrNull(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value != null) ps.setLong(idx, value);
        else ps.setNull(idx, java.sql.Types.NUMERIC);
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, java.sql.Types.NUMERIC);
    }

    private void setDoubleOrNull(PreparedStatement ps, int idx, Double value) throws SQLException {
        if (value != null) ps.setDouble(idx, value);
        else ps.setNull(idx, java.sql.Types.NUMERIC);
    }

    private void setStringOrNull(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value != null) ps.setString(idx, value);
        else ps.setNull(idx, java.sql.Types.VARCHAR);
    }

    /**
     * Like updateBoxOffice but only writes a column when it currently holds 0.
     * Used by secondary crawlers (BOM, Koimoi) to avoid overwriting data already
     * set by a higher-priority source.
     *
     * @return number of rows touched (rows where at least one column was updated)
     */
    public int updateBoxOfficeIfMissing(String movieName, String year,
                                         Long revenueUsd, Long budgetUsd) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"revenue\" = CASE WHEN COALESCE(\"revenue\", 0) = 0" +
            "                       THEN COALESCE(?, \"revenue\") ELSE \"revenue\" END," +
            "     \"budget\"  = CASE WHEN COALESCE(\"budget\",  0) = 0" +
            "                       THEN COALESCE(?, \"budget\")  ELSE \"budget\"  END" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, revenueUsd);
            ps.setObject(2, budgetUsd);
            ps.setString(3, movieName);
            ps.setString(4, year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    /**
     * Creates the currency_rate_xe table if it does not already exist.
     * Primary key is (rate_date, from_currency, to_currency) so multiple pairs can be stored.
     */
    public void ensureRateTableExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS currency_rate_xe (" +
                "  rate_date      DATE        NOT NULL," +
                "  from_currency  CHAR(3)     NOT NULL," +
                "  to_currency    CHAR(3)     NOT NULL," +
                "  rate           NUMERIC     NOT NULL," +
                "  fetched_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
                "  PRIMARY KEY (rate_date, from_currency, to_currency)" +
                ")"
            );
        }
        connection.commit();
    }

    /**
     * Loads all exchange rates for the given currency pair from currency_rate_xe.
     * Returns a map of date-string (YYYY-MM-DD) → rate.
     * Returns an empty map when the table does not yet exist.
     */
    public Map<String, Double> getExistingRates(String fromCurrency, String toCurrency) throws SQLException {
        Map<String, Double> result = new HashMap<>();
        String checkSql = "SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_name = 'currency_rate_xe'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(checkSql)) {
            if (!rs.next()) return result;
        }
        String sql = "SELECT rate_date::text, rate FROM currency_rate_xe " +
            "WHERE from_currency = ? AND to_currency = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, fromCurrency);
            ps.setString(2, toCurrency);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getDouble(2));
                }
            }
        }
        return result;
    }

    /**
     * Inserts or updates a single exchange-rate row.
     * On conflict (same date + currency pair) the rate and fetched_at are refreshed.
     */
    public void upsertExchangeRate(String date, String fromCurrency,
                                    String toCurrency, double rate) throws SQLException {
        String sql =
            "INSERT INTO currency_rate_xe (rate_date, from_currency, to_currency, rate, fetched_at) " +
            "VALUES (?::date, ?, ?, ?, NOW()) " +
            "ON CONFLICT (rate_date, from_currency, to_currency) " +
            "DO UPDATE SET rate = EXCLUDED.rate, fetched_at = NOW()";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date);
            ps.setString(2, fromCurrency);
            ps.setString(3, toCurrency);
            ps.setDouble(4, rate);
            ps.executeUpdate();
        }
        connection.commit();
    }

    private String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Rolls back the current transaction; suppresses the exception so callers stay clean. */
    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {}
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
