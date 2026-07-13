package com.lit.fire.flame.synopsis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the synopsis-enrichment service.
 * Mirrors CrawlerDatabaseService/YoutubeDatabaseService conventions: dedicated
 * connection, ADD COLUMN IF NOT EXISTS, update-by movie_name + release-year.
 */
public class SynopsisDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public SynopsisDatabaseService(String url, String user, String password,
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
     * Ensures the "synopsis" column exists, along with "synopsis_last_checked" — an
     * internal bookkeeping column (not one of the requested fields) that records the
     * last date a movie was searched and found nowhere, so movies neither source has
     * a page for aren't re-selected (and re-searched) every single cycle forever,
     * which would otherwise starve out the rest of the backlog. Safe to call on every
     * startup (uses ADD COLUMN IF NOT EXISTS).
     */
    public void ensureColumnExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"synopsis\" TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"synopsis_last_checked\" TEXT");
        }
        connection.commit();
    }

    /** One (movie_name, year) group awaiting synopsis enrichment. */
    public record Candidate(String movieName, String year, String releaseDate) {}

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    /**
     * Returns up to {@code limit} distinct (movie_name, year) groups still missing a
     * synopsis, restricted to already-released Indian-language movies with release year
     * strictly after 2000 and up to the current year, ordered most-recently-released
     * first (2026 down to 2001). A group with only a bare 4-digit year (no day precision)
     * in the current year is included — day-level "before today" filtering only applies
     * when a full YYYY-MM-DD release_date is available.
     *
     * A group is skipped when every missing row was already checked (by either source)
     * within the last {@code recheckDays} days — see markChecked().
     */
    public List<Candidate> getMoviesMissingSynopsis(int limit, int recheckDays) throws SQLException {
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr, " +
            "       COALESCE(MIN(release_date) FILTER (WHERE LENGTH(release_date) = 10), MIN(release_date)) AS release_date " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            "   AND LEFT(release_date, 4)::int > 2000 " +
            "   AND LEFT(release_date, 4)::int <= EXTRACT(YEAR FROM CURRENT_DATE)::int " +
            "   AND (LENGTH(release_date) <> 10 OR release_date::date <= CURRENT_DATE) " +
            "   AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "HAVING BOOL_OR(COALESCE(\"synopsis\", '') = '' AND " +
            "               (\"synopsis_last_checked\" IS NULL OR \"synopsis_last_checked\"::date < CURRENT_DATE - ?::int)) " +
            "ORDER BY yr DESC, movie_name " +
            "LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, recheckDays);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("movie_name");
                    String yr   = rs.getString("yr");
                    if (name == null || yr == null || !yr.matches("\\d{4}")) continue;
                    result.add(new Candidate(name, yr, rs.getString("release_date")));
                }
            }
        }
        return result;
    }

    /** Records that this movie was searched (by all enabled sources) today and found nowhere. */
    public void markChecked(String movieName, String year, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"synopsis_last_checked\" = ?" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?" +
            "   AND COALESCE(\"synopsis\", '') = ''";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, year);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Writes the synopsis for every row matching movieName + release year, but only
     * when that row doesn't already have one (so re-runs stay idempotent and a
     * higher-priority source's result is never clobbered by a later, lower-priority one).
     *
     * @return number of rows updated
     */
    public int updateSynopsisIfMissing(String movieName, String year, String synopsis) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"synopsis\" = ?" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?" +
            "   AND COALESCE(\"synopsis\", '') = ''";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, synopsis);
            ps.setString(2, movieName);
            ps.setString(3, year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
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
