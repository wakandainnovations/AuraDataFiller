package com.lit.fire.flame.tamilcsv;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Database operations for the Tamil CSV import (see TamilCsvImportService).
 *
 * Unlike the Hindi legacy-CSV backfill (LegacyCsvDatabaseService), this source carries a real
 * release_date, so matching uses (movie_name, release year, language='tamil') — the same
 * convention CreditsDatabaseService/EconomicDatabaseService/RuntimeBudgetDatabaseService use
 * elsewhere in this codebase. That year is exactly what the Hindi CSVs lacked, and its absence
 * there is what let unrelated same-titled movies from different files/languages overwrite each
 * other's data; matching on year here closes that hole without needing a frozen-placeholder
 * workaround.
 *
 * Never ALTERs the table: genre, runtime, budget, revenue, rating_10, directors,
 * production_companies, release_day, release_date are all pre-existing columns. A field is
 * only overwritten when currently empty/zero (fill-if-missing), same convention as
 * CreditsDatabaseService/RuntimeBudgetDatabaseService/LegacyCsvDatabaseService.
 */
public class TamilCsvDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public TamilCsvDatabaseService(String url, String user, String password,
                                    String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /** Supports the (lower(trim(movie_name)), release year) lookup used by findUniqueMatch. */
    public void ensureNameYearMatchIndex() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_lower_trim_name_year " +
                "ON " + q(tableName) + " (lower(trim(movie_name)), LEFT(release_date, 4))");
        }
        connection.commit();
    }

    public record ExistingRow(String movieName, String releaseDate) {}

    public record MatchResult(ExistingRow row, int matchCount) {
        public boolean isUnique() { return matchCount == 1; }
        public boolean isAmbiguous() { return matchCount > 1; }
    }

    /**
     * Finds Tamil-language row(s) matching the given title AND release year. A count of 0
     * means no matching movie exists yet; a count > 1 means the (title, year) pair itself is
     * ambiguous (e.g. two same-titled Tamil films the same year) and is left untouched.
     */
    public MatchResult findUniqueMatch(String movieName, String year) throws SQLException {
        String sql = "SELECT movie_name, release_date FROM " + q(tableName) +
            " WHERE lower(trim(movie_name)) = lower(trim(?)) AND lower(language) = 'tamil'" +
            "   AND release_date ~ '^[0-9]{4}' AND LEFT(release_date, 4) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, year);
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
     * Fills genre / runtime / budget / revenue / rating_10 / directors / production_companies /
     * release_day for one exact row, but only fields currently empty/zero and whose incoming
     * value is non-null.
     */
    public void fillMissingFields(String movieName, String releaseDate,
                                   String genre, Integer runtimeMinutes, Long budgetUsd, Long revenueUsd,
                                   BigDecimal rating10, String directors, String productionCompanies,
                                   String releaseDay) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "genre = CASE WHEN COALESCE(genre, '') = '' AND ? IS NOT NULL THEN ? ELSE genre END, " +
            "runtime = CASE WHEN COALESCE(runtime, 0) = 0 AND ? IS NOT NULL THEN ? ELSE runtime END, " +
            "budget = CASE WHEN COALESCE(budget, 0) = 0 AND ? IS NOT NULL THEN ? ELSE budget END, " +
            "revenue = CASE WHEN COALESCE(revenue, 0) = 0 AND ? IS NOT NULL THEN ? ELSE revenue END, " +
            "rating_10 = CASE WHEN COALESCE(rating_10, 0) = 0 AND ? IS NOT NULL THEN ? ELSE rating_10 END, " +
            "directors = CASE WHEN COALESCE(directors, '') = '' AND ? IS NOT NULL THEN ? ELSE directors END, " +
            "production_companies = CASE WHEN COALESCE(production_companies, '') = '' AND ? IS NOT NULL THEN ? ELSE production_companies END, " +
            "release_day = CASE WHEN COALESCE(release_day, '') = '' AND ? IS NOT NULL THEN ? ELSE release_day END " +
            "WHERE movie_name = ? AND release_date = ? AND lower(language) = 'tamil'";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, genre);
            ps.setString(i++, genre);
            setIntOrNull(ps, i++, runtimeMinutes);
            setIntOrNull(ps, i++, runtimeMinutes);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, revenueUsd);
            setLongOrNull(ps, i++, revenueUsd);
            setBigDecimalOrNull(ps, i++, rating10);
            setBigDecimalOrNull(ps, i++, rating10);
            ps.setString(i++, directors);
            ps.setString(i++, directors);
            ps.setString(i++, productionCompanies);
            ps.setString(i++, productionCompanies);
            ps.setString(i++, releaseDay);
            ps.setString(i++, releaseDay);
            ps.setString(i++, movieName);
            ps.setString(i, releaseDate);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Inserts a brand-new Tamil movie row with its real release_date (never a placeholder —
     * rows with an unparseable date are skipped upstream in TamilCsvImportService rather than
     * reaching here). Only columns with a non-null incoming value are included, so every
     * omitted column keeps its normal table default.
     *
     * @return false if a row with this exact (movie_name, release_date, "tamil") already
     *         exists (e.g. a duplicate title+date within the source file) — no-op in that case.
     */
    public boolean insertNewRow(String movieName, String releaseDate, String genre,
                                 Integer runtimeMinutes, Long budgetUsd, Long revenueUsd,
                                 BigDecimal rating10, String directors, String productionCompanies,
                                 String releaseDay) throws SQLException {
        List<String> cols = new ArrayList<>(List.of("movie_name", "release_date", "language"));
        List<Object> vals = new ArrayList<>(List.of(movieName, releaseDate, "tamil"));

        addIfPresent(cols, vals, "genre", genre);
        addIfPresent(cols, vals, "runtime", runtimeMinutes);
        addIfPresent(cols, vals, "budget", budgetUsd);
        addIfPresent(cols, vals, "revenue", revenueUsd);
        addIfPresent(cols, vals, "rating_10", rating10);
        addIfPresent(cols, vals, "directors", directors);
        addIfPresent(cols, vals, "production_companies", productionCompanies);
        addIfPresent(cols, vals, "release_day", releaseDay);

        String colList = cols.stream().map(this::q).collect(Collectors.joining(", "));
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + q(tableName) + " (" + colList + ") VALUES (" + placeholders + ") " +
            "ON CONFLICT (movie_name, release_date, language) DO NOTHING";

        int rows;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < vals.size(); i++) {
                ps.setObject(i + 1, vals.get(i));
            }
            rows = ps.executeUpdate();
        }
        connection.commit();
        return rows > 0;
    }

    private void addIfPresent(List<String> cols, List<Object> vals, String column, Object value) {
        if (value == null) return;
        cols.add(column);
        vals.add(value);
    }

    // ---- currency_rate_xe cache (same table/schema as CrawlerDatabaseService/LegacyCsvDatabaseService) ----

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

    private void setBigDecimalOrNull(PreparedStatement ps, int idx, BigDecimal value) throws SQLException {
        if (value != null) ps.setBigDecimal(idx, value);
        else ps.setNull(idx, java.sql.Types.NUMERIC);
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
