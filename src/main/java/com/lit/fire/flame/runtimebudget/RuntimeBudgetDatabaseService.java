package com.lit.fire.flame.runtimebudget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database operations for the runtime/budget backfill over existing rows in
 * movies_data_collection. Mirrors CreditsDatabaseService/EconomicDatabaseService
 * conventions: dedicated connection, ADD COLUMN IF NOT EXISTS, update-by movie_name +
 * release-year, with a bookkeeping "last checked" column so movies sacnilk has no data
 * for aren't re-selected every cycle forever.
 *
 * Also owns the currency_rate_xe cache table (same schema as CrawlerDatabaseService)
 * since budget values need INR-Crore-to-USD conversion at the movie's release-date rate.
 */
public class RuntimeBudgetDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public RuntimeBudgetDatabaseService(String url, String user, String password,
                                         String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (EconomicDatabaseService, CrawlerDatabaseService.queryMissingBoxOffice, CreditsDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

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
     * Ensures "runtime" and "budget" exist, along with "runtime_budget_last_checked" — an
     * internal bookkeeping column (not one of the requested fields) that records the last
     * date a movie was searched and found nowhere, so movies sacnilk has no page (or no
     * runtime/budget data) for aren't re-selected every single cycle forever.
     * Safe to call on every startup (uses ADD COLUMN IF NOT EXISTS).
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"runtime\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"budget\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"runtime_budget_last_checked\" TEXT");
        }
        connection.commit();
    }

    /**
     * One (movie_name, year, earliest release_date) group awaiting runtime/budget enrichment.
     * needsRuntime/needsBudget record which field(s) were actually 0 for this group at query
     * time, so callers can tell a fully-satisfied candidate (skip future recheck) apart from
     * one still missing a field no available source could supply this cycle.
     */
    public record Candidate(String movieName, String year, String releaseDate,
                             boolean needsRuntime, boolean needsBudget) {}

    /**
     * Returns up to {@code limit} distinct (movie_name, year) groups still missing "runtime"
     * and/or "budget" (0 or NULL — the NUMERIC default), restricted to Indian-language movies
     * with release year strictly after 2000 and up to the current year, ordered most-recently-
     * released first. The earliest release_date within the group is included so the caller can
     * look up the historical INR→USD rate for that specific date.
     *
     * A group is skipped when every row missing either field was already checked within the
     * last {@code recheckDays} days — see markChecked().
     */
    public List<Candidate> getMoviesMissingRuntimeBudget(int limit, int recheckDays) throws SQLException {
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr, MIN(release_date) AS release_date, " +
            "       BOOL_OR(COALESCE(\"runtime\", 0) = 0) AS needs_runtime, " +
            "       BOOL_OR(COALESCE(\"budget\", 0) = 0) AS needs_budget " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            "   AND LEFT(release_date, 4)::int > 2000 " +
            "   AND LEFT(release_date, 4)::int <= EXTRACT(YEAR FROM CURRENT_DATE)::int " +
            "   AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "HAVING BOOL_OR((COALESCE(\"runtime\", 0) = 0 OR COALESCE(\"budget\", 0) = 0) AND " +
            "               (\"runtime_budget_last_checked\" IS NULL OR \"runtime_budget_last_checked\"::date < CURRENT_DATE - ?::int)) " +
            "ORDER BY yr DESC, movie_name " +
            "LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, recheckDays);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name        = rs.getString("movie_name");
                    String yr          = rs.getString("yr");
                    String releaseDate = rs.getString("release_date");
                    if (name == null || yr == null || !yr.matches("\\d{4}")) continue;
                    result.add(new Candidate(name, yr, releaseDate,
                        rs.getBoolean("needs_runtime"), rs.getBoolean("needs_budget")));
                }
            }
        }
        return result;
    }

    /** Records that this movie was searched today and still has a field missing. */
    public void markChecked(String movieName, String year, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"runtime_budget_last_checked\" = ?" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?" +
            "   AND (COALESCE(\"runtime\", 0) = 0 OR COALESCE(\"budget\", 0) = 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, year);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Writes runtime/budget for every row matching movieName + release year, but only fills
     * a field that's currently 0/NULL — so a higher-confidence value already sitting in the
     * table is never clobbered, and re-runs stay idempotent. Either parameter may be null
     * (sacnilk had nothing for that particular field).
     *
     * @return number of rows updated
     */
    public int updateRuntimeBudgetIfMissing(String movieName, String year,
                                             Integer runtimeMinutes, Long budgetUsd) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"runtime\" = CASE WHEN ? IS NOT NULL AND COALESCE(\"runtime\", 0) = 0" +
            "                   THEN ? ELSE \"runtime\" END," +
            "     \"budget\"  = CASE WHEN ? IS NOT NULL AND COALESCE(\"budget\", 0) <= 0" +
            "                   THEN ? ELSE \"budget\" END" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            setIntOrNull(ps, i++, runtimeMinutes);
            setIntOrNull(ps, i++, runtimeMinutes);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, budgetUsd);
            ps.setString(i++, movieName);
            ps.setString(i,   year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, Types.NUMERIC);
    }

    private void setLongOrNull(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value != null) ps.setLong(idx, value);
        else ps.setNull(idx, Types.NUMERIC);
    }

    // ---- currency_rate_xe cache (same table/schema as CrawlerDatabaseService) ----

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
