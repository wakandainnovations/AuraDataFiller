package com.lit.fire.flame.youtube;

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
 * Database operations for the YouTube promo-metrics enrichment service.
 * Mirrors CrawlerDatabaseService's conventions: dedicated connection,
 * ADD COLUMN IF NOT EXISTS, update-by movie_name + release-year.
 */
public class YoutubeDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public YoutubeDatabaseService(String url, String user, String password,
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
     * Ensures every column written by this service exists. Safe to call on every
     * startup (uses ADD COLUMN IF NOT EXISTS). "youtube_last_checked" is an internal
     * bookkeeping column (not one of the requested metrics) that records the last
     * date a movie was searched, so movies YouTube has no video for yet don't get
     * re-searched (and re-billed 100 quota units) every single cycle forever.
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            for (String col : new String[]{
                "\"trailer_release_date\" TEXT",
                "\"teaser_release_date\" TEXT",
                "\"first_song_release_date\" TEXT",
                "\"trailer_days_to_release\" INTEGER",
                "\"teaser_days_to_release\" INTEGER",
                "\"song_days_to_release\" INTEGER",
                "\"trailer_views\" BIGINT",
                "\"teaser_views\" BIGINT",
                "\"song_views\" BIGINT",
                "\"trailer_comments\" BIGINT",
                "\"teaser_comments\" BIGINT",
                "\"song_comments\" BIGINT",
                "\"youtube_last_checked\" TEXT"
            }) {
                stmt.execute("ALTER TABLE " + q(tableName) + " ADD COLUMN IF NOT EXISTS " + col);
            }
        }
        connection.commit();
    }

    /** One row awaiting YouTube enrichment: movie_name, 4-digit release year, earliest release_date. */
    public static class Candidate {
        public final String movieName;
        public final String year;
        public final String releaseDate;
        public final boolean needsTrailer;
        public final boolean needsTeaser;
        public final boolean needsSong;

        Candidate(String movieName, String year, String releaseDate,
                  boolean needsTrailer, boolean needsTeaser, boolean needsSong) {
            this.movieName = movieName;
            this.year = year;
            this.releaseDate = releaseDate;
            this.needsTrailer = needsTrailer;
            this.needsTeaser = needsTeaser;
            this.needsSong = needsSong;
        }
    }

    /**
     * "First single/song" is an Indian-cinema promotional convention with essentially no
     * equivalent for most Hollywood/international films (confirmed empirically: searching for
     * one against a movie with no real promotional single reliably surfaces unrelated content —
     * reaction videos, same-named songs by unrelated artists, movie-recap channels — no matter
     * how the query/matching is tuned). Restricting song search to Indian-language rows avoids
     * wasting quota on searches that can't succeed and avoids writing wrong data. Mirrors the
     * same list used by CrawlerDatabaseService.getMoviesMissingBoxOfficeIndian.
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    /**
     * Returns up to {@code limit} distinct (movie_name, year) pairs released after 2010,
     * most recently released first, that are still missing at least one of
     * trailer/teaser/first-song data and were not already checked within the last
     * {@code recheckDays} days.
     */
    public List<Candidate> getCandidates(int recheckDays, int limit) throws SQLException {
        // Some movies have both a bare-year row (e.g. "2025", from an IMDb-style import) and a
        // full-date row (e.g. "2025-07-09", from a regional-language import) for the same
        // (movie_name, year) group. Plain MIN(release_date) would pick the bare-year string —
        // it sorts lexicographically before any same-year full date — leaving no day-level
        // precision to compute days-to-release from. Prefer a full YYYY-MM-DD date when the
        // group has one; only fall back to a bare year (and skip day-level fields later) when
        // no row in the group has full precision.
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr, " +
            "       COALESCE(MIN(release_date) FILTER (WHERE LENGTH(release_date) = 10), MIN(release_date)) AS release_date, " +
            "       BOOL_OR(trailer_release_date IS NULL) AS needs_trailer, " +
            "       BOOL_OR(teaser_release_date IS NULL) AS needs_teaser, " +
            "       (BOOL_OR(first_song_release_date IS NULL) AND BOOL_OR(LOWER(language) IN " + INDIAN_LANGUAGES_SQL + ")) AS needs_song " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            "   AND LEFT(release_date, 4)::int > 2010 " +
            "   AND release_date <= CURRENT_DATE::text " + // only already-released movies have promo videos to find
            "   AND (trailer_release_date IS NULL OR teaser_release_date IS NULL " +
            "        OR (first_song_release_date IS NULL AND LOWER(language) IN " + INDIAN_LANGUAGES_SQL + ")) " +
            "   AND (youtube_last_checked IS NULL OR youtube_last_checked::date < CURRENT_DATE - ?) " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
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
                    result.add(new Candidate(
                        name, yr, rs.getString("release_date"),
                        rs.getBoolean("needs_trailer"),
                        rs.getBoolean("needs_teaser"),
                        rs.getBoolean("needs_song")
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Looks up a single (movie_name, year) group for manual spot-checks, forcing all three
     * categories (trailer/teaser/song) to be searched regardless of what's already filled in
     * or when it was last checked. Returns null if no matching row exists.
     */
    public Candidate findMovieForTest(String movieName, String year) throws SQLException {
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr, " +
            "       COALESCE(MIN(release_date) FILTER (WHERE LENGTH(release_date) = 10), MIN(release_date)) AS release_date " +
            "FROM " + q(tableName) +
            " WHERE movie_name = ? AND LEFT(release_date, 4) = ? " +
            "GROUP BY movie_name, LEFT(release_date, 4)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Candidate(rs.getString("movie_name"), rs.getString("yr"),
                    rs.getString("release_date"), true, true, true);
            }
        }
    }

    /**
     * Writes YouTube-derived fields for every row matching movieName + release year.
     * Only fills columns that are currently NULL (COALESCE), so a partially-enriched
     * row is topped up rather than overwritten and re-runs stay idempotent.
     */
    public int updateYoutubeData(String movieName, String year, YoutubeRecord record) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "\"trailer_release_date\" = COALESCE(\"trailer_release_date\", ?), " +
            "\"teaser_release_date\" = COALESCE(\"teaser_release_date\", ?), " +
            "\"first_song_release_date\" = COALESCE(\"first_song_release_date\", ?), " +
            "\"trailer_days_to_release\" = COALESCE(\"trailer_days_to_release\", ?), " +
            "\"teaser_days_to_release\" = COALESCE(\"teaser_days_to_release\", ?), " +
            "\"song_days_to_release\" = COALESCE(\"song_days_to_release\", ?), " +
            "\"trailer_views\" = COALESCE(\"trailer_views\", ?), " +
            "\"teaser_views\" = COALESCE(\"teaser_views\", ?), " +
            "\"song_views\" = COALESCE(\"song_views\", ?), " +
            "\"trailer_comments\" = COALESCE(\"trailer_comments\", ?), " +
            "\"teaser_comments\" = COALESCE(\"teaser_comments\", ?), " +
            "\"song_comments\" = COALESCE(\"song_comments\", ?) " +
            "WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, record.trailerDate != null ? record.trailerDate.toString() : null);
            ps.setString(i++, record.teaserDate != null ? record.teaserDate.toString() : null);
            ps.setString(i++, record.songDate != null ? record.songDate.toString() : null);
            setIntOrNull(ps, i++, record.trailerDaysToRelease);
            setIntOrNull(ps, i++, record.teaserDaysToRelease);
            setIntOrNull(ps, i++, record.songDaysToRelease);
            setLongOrNull(ps, i++, record.trailerViews);
            setLongOrNull(ps, i++, record.teaserViews);
            setLongOrNull(ps, i++, record.songViews);
            setLongOrNull(ps, i++, record.trailerComments);
            setLongOrNull(ps, i++, record.teaserComments);
            setLongOrNull(ps, i++, record.songComments);
            ps.setString(i++, movieName);
            ps.setString(i, year);
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    /** Records that this movie was searched today, so it isn't re-searched every cycle. */
    public void markChecked(String movieName, String year, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"youtube_last_checked\" = ? WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, year);
            ps.executeUpdate();
        }
        connection.commit();
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, Types.INTEGER);
    }

    private void setLongOrNull(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value != null) ps.setLong(idx, value);
        else ps.setNull(idx, Types.BIGINT);
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
