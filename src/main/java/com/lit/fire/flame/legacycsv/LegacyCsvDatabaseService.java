package com.lit.fire.flame.legacycsv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Database operations for the legacy Bollywood CSV backfill (see LegacyCsvBackfillService).
 * Only ever UPDATEs pre-existing rows in movies_data_collection by (movie_name, language) —
 * never inserts new rows and never ALTERs the table, since the source CSVs carry no
 * release_date and every target field (genre, release_event_type, revenue, budget,
 * number_of_screens) is a pre-existing column.
 *
 * A field is only overwritten when it is currently empty/zero, so data already sourced by
 * the sacnilk/BOM/koimoi crawlers is never clobbered by this one-off historical dataset.
 */
public class LegacyCsvDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public LegacyCsvDatabaseService(String url, String user, String password,
                                     String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /** One movie row already present in the DB, keyed by its exact (movie_name, release_date). */
    public record ExistingRow(String movieName, String releaseDate) {}

    /** Outcome of matching a CSV title against the DB: exactly one row, none, or more than one. */
    public record MatchResult(ExistingRow row, int matchCount) {
        public boolean isUnique() { return matchCount == 1; }
        public boolean isAmbiguous() { return matchCount > 1; }
    }

    /**
     * Finds the Hindi-language row(s) matching the given movie name (case-insensitive).
     * Callers should only act on {@link MatchResult#isUnique()} — a count of 0 means no
     * matching movie exists in the DB, and a count > 1 means the title is ambiguous (e.g. a
     * remake sharing its title with an earlier Hindi release); the source CSV carries no
     * year to disambiguate either case, so both are left untouched rather than guessed at.
     */
    public MatchResult findUniqueHindiMatch(String movieName) throws SQLException {
        String sql = "SELECT movie_name, release_date FROM " + q(tableName) +
            " WHERE lower(trim(movie_name)) = lower(trim(?)) AND lower(language) = 'hindi'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            try (ResultSet rs = ps.executeQuery()) {
                ExistingRow found = null;
                int count = 0;
                while (rs.next()) {
                    count++;
                    found = new ExistingRow(rs.getString("movie_name"), rs.getString("release_date"));
                }
                return new MatchResult(count == 1 ? found : null, count);
            }
        }
    }

    /**
     * Fills genre / release_event_type / revenue / budget / number_of_screens for one exact
     * row, but only the fields currently empty/zero/null and whose incoming value is non-null.
     *
     * @return number of columns actually changed by this call
     */
    public int fillMissingFields(String movieName, String releaseDate,
                                  String genre, String releaseEventType,
                                  Long revenueUsd, Long budgetUsd, Integer numberOfScreens) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "genre = CASE WHEN COALESCE(genre, '') = '' AND ? IS NOT NULL THEN ? ELSE genre END, " +
            "release_event_type = CASE WHEN COALESCE(release_event_type, '') = '' AND ? IS NOT NULL THEN ? ELSE release_event_type END, " +
            "revenue = CASE WHEN COALESCE(revenue, 0) = 0 AND ? IS NOT NULL THEN ? ELSE revenue END, " +
            "budget = CASE WHEN COALESCE(budget, 0) = 0 AND ? IS NOT NULL THEN ? ELSE budget END, " +
            "number_of_screens = CASE WHEN number_of_screens IS NULL AND ? IS NOT NULL THEN ? ELSE number_of_screens END " +
            "WHERE movie_name = ? AND release_date = ? AND lower(language) = 'hindi'";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, genre);
            ps.setString(i++, genre);
            ps.setString(i++, releaseEventType);
            ps.setString(i++, releaseEventType);
            setLongOrNull(ps, i++, revenueUsd);
            setLongOrNull(ps, i++, revenueUsd);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, budgetUsd);
            setIntOrNull(ps, i++, numberOfScreens);
            setIntOrNull(ps, i++, numberOfScreens);
            ps.setString(i++, movieName);
            ps.setString(i, releaseDate);
            ps.executeUpdate();
        }
        connection.commit();

        // Report how many of the incoming fields were non-null (an upper bound on what
        // changed — exact-per-column deltas aren't worth a second round-trip for a one-off tool).
        int provided = 0;
        if (genre != null) provided++;
        if (releaseEventType != null) provided++;
        if (revenueUsd != null) provided++;
        if (budgetUsd != null) provided++;
        if (numberOfScreens != null) provided++;
        return provided;
    }

    // ---- currency_rate_xe cache (same table/schema as CrawlerDatabaseService/RuntimeBudgetDatabaseService) ----

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

    public Map<String, Double> getExistingRates(String fromCurrency, String toCurrency) throws SQLException {
        Map<String, Double> result = new HashMap<>();
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

    // ---- private helpers ----

    private void setLongOrNull(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value != null) ps.setLong(idx, value);
        else ps.setNull(idx, java.sql.Types.NUMERIC);
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, java.sql.Types.INTEGER);
    }

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
