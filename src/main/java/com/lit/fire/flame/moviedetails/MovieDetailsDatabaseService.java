package com.lit.fire.flame.moviedetails;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the TMDB movie-details backfill (see MovieDetailsService).
 * Mirrors CreditsDatabaseService conventions: dedicated connection, ADD COLUMN IF NOT EXISTS,
 * grouped by (movie_name, release year) rather than per exact row — the same physical film's
 * rating/runtime/genre/country/production companies/directors/CBFC certificate don't vary
 * across its different language-dub rows, so one TMDB lookup's answer is written to every row in
 * the group, same as CreditsDatabaseService already does for directors/production_companies.
 *
 * Every field targeted here already exists as a column on movies_data_collection from other
 * sources (rating_10: SacnilkCrawlerService, and historically some CSV imports' "vote_average" —
 * itself a TMDB field, see ColumnMapper; runtime: RuntimeBudgetCrawlerService; genre, directors,
 * production_companies: CreditsCrawlerService/SacnilkCrawlerService; country: CSV import) — this
 * service only ever fills a value that's still missing (COALESCE/NULLIF, same as those
 * services), so it never clobbers a higher-confidence value obtained elsewhere. The lone
 * exception is cbfc_rating, which as of this writing has no other writer at all.
 */
public class MovieDetailsDatabaseService implements AutoCloseable {

    private static final String LAST_CHECKED_COL = "movie_details_last_checked";

    private final Connection connection;
    private final String tableName;

    public MovieDetailsDatabaseService(String url, String user, String password,
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
     * Ensures every target column exists, plus "movie_details_last_checked" — an internal
     * bookkeeping column (not one of the requested fields) that records the last date a movie
     * group was looked up and TMDB had no confident match/nothing usable, so it isn't
     * resubmitted every single cycle. Safe to call on every startup (uses ADD COLUMN IF NOT
     * EXISTS).
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"rating_10\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"runtime\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"genre\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"country\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"production_companies\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"directors\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS \"cbfc_rating\" TEXT");
            stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS " + q(LAST_CHECKED_COL) + " TEXT");
        }
        connection.commit();
    }

    /** One (movie_name, release year) group awaiting movie-details enrichment; {@code languages} lists every distinct language among its rows (read-only context — never written to). */
    public record Candidate(String movieName, String year, String languages) {}

    private static final String MISSING_FIELD_SQL =
        "(COALESCE(\"rating_10\", 0) = 0 " +
        " OR COALESCE(\"runtime\", 0) = 0 " +
        " OR COALESCE(\"genre\", '') = '' " +
        " OR COALESCE(\"country\", '') = '' " +
        " OR COALESCE(\"production_companies\", '') = '' " +
        " OR COALESCE(\"directors\", '') = '' " +
        " OR COALESCE(\"cbfc_rating\", '') = '')";

    /**
     * Returns up to {@code limit} distinct (movie_name, year) groups still missing at least one
     * target field, most-recently-released first. Unlike the earlier AuraLLM-based version,
     * there is no knowledge-cutoff restriction here — TMDB is looked up live, so every release
     * year (including future/upcoming ones already in the table) is a valid candidate.
     *
     * A group is skipped when every row missing a field was already checked within the last
     * {@code recheckDays} days — see markChecked()/updateDetailsIfMissing(), both of which stamp
     * the bookkeeping column.
     */
    public List<Candidate> getCandidates(int recheckDays, int limit) throws SQLException {
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr, STRING_AGG(DISTINCT language, ', ') AS languages " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            " GROUP BY movie_name, LEFT(release_date, 4) " +
            " HAVING BOOL_OR(" + MISSING_FIELD_SQL + " AND " +
            "                (" + q(LAST_CHECKED_COL) + " IS NULL OR " + q(LAST_CHECKED_COL) + "::date < CURRENT_DATE - ?::int)) " +
            " ORDER BY yr DESC, movie_name " +
            " LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, recheckDays);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("movie_name");
                    String yr   = rs.getString("yr");
                    if (name == null || yr == null || !yr.matches("\\d{4}")) continue;
                    result.add(new Candidate(name, yr, rs.getString("languages")));
                }
            }
        }
        return result;
    }

    /**
     * Writes TMDB's answer for exactly one (movieName, year) group, but only into fields
     * currently empty/zero on each matching row (idempotent re-runs never clobber an existing,
     * possibly-higher-confidence value from another source), and stamps the bookkeeping column
     * with today's date either way. Any parameter may be null (TMDB had nothing for that
     * particular field — e.g. no votes yet, no India release/certification on file).
     *
     * @return number of rows updated
     */
    public int updateDetailsIfMissing(String movieName, String year, MovieDetails details,
                                       String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "\"rating_10\" = CASE WHEN COALESCE(\"rating_10\", 0) = 0 AND ? IS NOT NULL THEN ? ELSE \"rating_10\" END, " +
            "\"runtime\"   = CASE WHEN COALESCE(\"runtime\", 0) = 0 AND ? IS NOT NULL THEN ? ELSE \"runtime\" END, " +
            "\"genre\"                 = COALESCE(NULLIF(\"genre\", ''), ?), " +
            "\"country\"               = COALESCE(NULLIF(\"country\", ''), ?), " +
            "\"production_companies\"  = COALESCE(NULLIF(\"production_companies\", ''), ?), " +
            "\"directors\"             = COALESCE(NULLIF(\"directors\", ''), ?), " +
            "\"cbfc_rating\"           = COALESCE(NULLIF(\"cbfc_rating\", ''), ?), " +
            q(LAST_CHECKED_COL) + " = ? " +
            "WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            setNumericOrNull(ps, i++, details.rating());
            setNumericOrNull(ps, i++, details.rating());
            setNumericOrNull(ps, i++, details.runtimeMinutes());
            setNumericOrNull(ps, i++, details.runtimeMinutes());
            ps.setString(i++, details.genre());
            ps.setString(i++, details.country());
            ps.setString(i++, details.productionCompanies());
            ps.setString(i++, details.directors());
            ps.setString(i++, details.cbfcRating());
            ps.setString(i++, checkedDate);
            ps.setString(i++, movieName);
            ps.setString(i, year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    /** Records that this movie group was checked today and still has a field missing. */
    public void markChecked(String movieName, String year, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET " + q(LAST_CHECKED_COL) + " = ?" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, year);
            ps.executeUpdate();
        }
        connection.commit();
    }

    private static void setNumericOrNull(PreparedStatement ps, int index, Number value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setDouble(index, value.doubleValue());
        }
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
