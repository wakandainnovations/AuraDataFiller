package com.lit.fire.flame.legacycsv;

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
 * Database operations for the legacy Bollywood CSV backfill (see LegacyCsvBackfillService).
 * Never ALTERs the table — every target field (genre, release_event_type, revenue, budget,
 * number_of_screens, runtime, rating_10) is a pre-existing column.
 *
 * Mostly UPDATEs pre-existing rows by (movie_name, language) — a field is only overwritten
 * when it is currently empty/zero, so data already sourced by the sacnilk/BOM/koimoi crawlers
 * is never clobbered by this one-off historical dataset. A title with no existing match at all
 * is, for CSV callers, INSERTed as a brand-new row instead (see insertNewRow).
 */
public class LegacyCsvDatabaseService implements AutoCloseable {

    /**
     * Placeholder for release_date on a brand-new row inserted by insertNewRow — the source
     * CSVs carry no date at all. Deliberately non-numeric so LEFT(release_date,4) ~ '^[0-9]{4}$'
     * checks used throughout this codebase's other year-based enrichment naturally exclude
     * these rows instead of being fed a fabricated year.
     */
    private static final String UNKNOWN_RELEASE_DATE = "Unknown";

    private final Connection connection;
    private final String tableName;

    public LegacyCsvDatabaseService(String url, String user, String password,
                                     String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /**
     * Creates a functional index supporting findUniqueHindiMatch's lower(trim(movie_name))
     * lookup. Without it, every title lookup is a full sequential scan of the whole table
     * (390K+ rows) — negligible per row, but it adds up across a few thousand rows. Safe to
     * call on every run.
     */
    public void ensureNameMatchIndex() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_lower_trim_name " +
                "ON " + q(tableName) + " (lower(trim(movie_name)))");
        }
        connection.commit();
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
     *
     * Deliberately excludes rows whose release_date is the {@link #UNKNOWN_RELEASE_DATE}
     * placeholder (a brand-new row from insertNewRow): matching on title alone is exactly how
     * an unrelated same-titled movie from a completely different source/language ended up
     * writing its own real facts (budget, runtime, rating) onto the wrong movie's row — e.g. a
     * Bollywood "Fight Club" freshly inserted with no year, later silently enriched with the
     * 1999 Hollywood "Fight Club"'s budget/runtime/IMDb rating by an unrelated file's pass over
     * the same title. Once a row has no year, it must stay frozen (no further fills from this
     * pipeline) until a human gives it a real release_date.
     */
    public MatchResult findUniqueHindiMatch(String movieName) throws SQLException {
        String sql = "SELECT movie_name, release_date FROM " + q(tableName) +
            " WHERE lower(trim(movie_name)) = lower(trim(?)) AND lower(language) = 'hindi'" +
            "   AND release_date ~ '^[0-9]{4}'";
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
     * Fills genre / release_event_type / revenue / budget / number_of_screens / runtime /
     * rating_10 for one exact row, but only the fields currently empty/zero/null and whose
     * incoming value is non-null.
     *
     * @return number of columns actually changed by this call
     */
    public int fillMissingFields(String movieName, String releaseDate,
                                  String genre, String releaseEventType,
                                  Long revenueUsd, Long budgetUsd, Integer numberOfScreens,
                                  Integer runtimeMinutes, BigDecimal rating10) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "genre = CASE WHEN COALESCE(genre, '') = '' AND ? IS NOT NULL THEN ? ELSE genre END, " +
            "release_event_type = CASE WHEN COALESCE(release_event_type, '') = '' AND ? IS NOT NULL THEN ? ELSE release_event_type END, " +
            "revenue = CASE WHEN COALESCE(revenue, 0) = 0 AND ? IS NOT NULL THEN ? ELSE revenue END, " +
            "budget = CASE WHEN COALESCE(budget, 0) = 0 AND ? IS NOT NULL THEN ? ELSE budget END, " +
            "number_of_screens = CASE WHEN number_of_screens IS NULL AND ? IS NOT NULL THEN ? ELSE number_of_screens END, " +
            "runtime = CASE WHEN COALESCE(runtime, 0) = 0 AND ? IS NOT NULL THEN ? ELSE runtime END, " +
            "rating_10 = CASE WHEN COALESCE(rating_10, 0) = 0 AND ? IS NOT NULL THEN ? ELSE rating_10 END " +
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
            setIntOrNull(ps, i++, runtimeMinutes);
            setIntOrNull(ps, i++, runtimeMinutes);
            setBigDecimalOrNull(ps, i++, rating10);
            setBigDecimalOrNull(ps, i++, rating10);
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
        if (runtimeMinutes != null) provided++;
        if (rating10 != null) provided++;
        return provided;
    }

    /**
     * Inserts a brand-new movie row for a title with no existing match at all. release_date is
     * set to {@link #UNKNOWN_RELEASE_DATE} (the source CSV has no date) and language to
     * "hindi" (this importer only ever matches/creates Hindi rows) — the caller is expected to
     * reconcile the real release_date/possible duplicates by hand afterwards. Only the columns
     * with a non-null incoming value are included in the INSERT, so every omitted column keeps
     * its normal table default (0 for the NUMERIC money/rating/runtime columns, NULL for TEXT
     * and number_of_screens).
     *
     * @return false if a row with this exact (movie_name, "Unknown", "hindi") already exists
     *         (e.g. a duplicate title within the same source file) — no-op in that case.
     */
    public boolean insertNewRow(String movieName, String genre, String releaseEventType,
                                 Long revenueUsd, Long budgetUsd, Integer numberOfScreens,
                                 Integer runtimeMinutes, BigDecimal rating10) throws SQLException {
        List<String> cols = new ArrayList<>(List.of("movie_name", "release_date", "language"));
        List<Object> vals = new ArrayList<>(List.of(movieName, UNKNOWN_RELEASE_DATE, "hindi"));

        addIfPresent(cols, vals, "genre", genre);
        addIfPresent(cols, vals, "release_event_type", releaseEventType);
        addIfPresent(cols, vals, "revenue", revenueUsd);
        addIfPresent(cols, vals, "budget", budgetUsd);
        addIfPresent(cols, vals, "number_of_screens", numberOfScreens);
        addIfPresent(cols, vals, "runtime", runtimeMinutes);
        addIfPresent(cols, vals, "rating_10", rating10);

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
