package com.lit.fire.flame.releaseevent;

import com.lit.fire.flame.mapper.ColumnMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the release-event backfill over existing Indian-language rows in
 * movies_data_collection. Mirrors CreditsDatabaseService/EconomicDatabaseService conventions:
 * dedicated connection, ADD COLUMN IF NOT EXISTS, update-only-if-missing, a bookkeeping
 * "*_last_checked" column so a row AuraLLM genuinely found nothing for isn't resubmitted every
 * single cycle.
 */
public class ReleaseEventDatabaseService implements AutoCloseable {

    private static final String LAST_CHECKED_COL = "release_event_last_checked";

    private final Connection connection;
    private final String tableName;

    public ReleaseEventDatabaseService(String url, String user, String password,
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

    /** Ensures the three event columns and the bookkeeping column exist. Safe on every startup. */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(ColumnMapper.EVENT_TYPE_COL) + " TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(ColumnMapper.EVENT_NAME_COL) + " TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(ColumnMapper.EVENT_DETAIL_COL) + " TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(LAST_CHECKED_COL) + " TEXT");
        }
        connection.commit();
    }

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (CreditsDatabaseService, EconomicDatabaseService, MarketingTacticsDatabaseService,
     * CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    /** One (movie_name, release_date, language) row awaiting release-event classification. */
    public record Candidate(String movieName, String releaseDate, String language) {}

    /**
     * Returns up to {@code limit} Indian-language rows with a full YYYY-MM-DD release_date on
     * or before today, still missing "release_event_type", most-recently-released first. A row
     * is skipped when it was already checked within the last {@code recheckDays} days (see
     * markChecked/updateEventIfMissing, both of which stamp the bookkeeping column).
     */
    public List<Candidate> getCandidates(int limit, int recheckDays) throws SQLException {
        String sql =
            "SELECT \"movie_name\", \"release_date\", \"language\" " +
            "FROM " + q(tableName) + " " +
            "WHERE \"release_date\" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' " +
            "  AND \"release_date\"::date <= CURRENT_DATE " +
            "  AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "  AND COALESCE(" + q(ColumnMapper.EVENT_TYPE_COL) + ", '') = '' " +
            "  AND (" + q(LAST_CHECKED_COL) + " IS NULL OR " + q(LAST_CHECKED_COL) + "::date < CURRENT_DATE - ?::int) " +
            "ORDER BY \"release_date\" DESC, \"movie_name\" " +
            "LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, recheckDays);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Candidate(
                        rs.getString("movie_name"),
                        rs.getString("release_date"),
                        rs.getString("language")));
                }
            }
        }
        return result;
    }

    /**
     * Writes the classified event for exactly one row, but only into columns currently empty
     * (idempotent re-runs never clobber an existing value), and stamps the bookkeeping column
     * with today's date either way.
     */
    public void updateEventIfMissing(String movieName, String releaseDate, String language,
                                      String eventType, String eventName, String eventDetail,
                                      String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            q(ColumnMapper.EVENT_TYPE_COL)   + " = COALESCE(NULLIF(" + q(ColumnMapper.EVENT_TYPE_COL)   + ", ''), ?), " +
            q(ColumnMapper.EVENT_NAME_COL)   + " = COALESCE(NULLIF(" + q(ColumnMapper.EVENT_NAME_COL)   + ", ''), ?), " +
            q(ColumnMapper.EVENT_DETAIL_COL) + " = COALESCE(NULLIF(" + q(ColumnMapper.EVENT_DETAIL_COL) + ", ''), ?), " +
            q(LAST_CHECKED_COL) + " = ? " +
            "WHERE \"movie_name\" = ? AND \"release_date\" = ? AND \"language\" = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, eventName);
            ps.setString(3, eventDetail);
            ps.setString(4, checkedDate);
            ps.setString(5, movieName);
            ps.setString(6, releaseDate);
            ps.setString(7, language);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Returns up to {@code limit} already-released Indian-language rows currently classified
     * "Normal" OR not yet classified at all (NULL/blank release_event_type), most-recently-
     * released first — candidates for {@link ReleaseEventService#runRecheckNormalOnce}
     * re-examining them against a taxonomy that grew after "Normal" rows were first classified
     * (e.g. Cricket/Football World Cup, state-election awareness); NULL/blank rows are included
     * because they've never been checked against that taxonomy either. Unlike
     * {@link #getCandidates}, this ignores the "*_last_checked" bookkeeping column entirely —
     * it's a targeted one-off recheck, not the incremental main backlog.
     */
    public List<Candidate> getNormalCandidates(int limit) throws SQLException {
        String sql =
            "SELECT \"movie_name\", \"release_date\", \"language\" " +
            "FROM " + q(tableName) + " " +
            "WHERE \"release_date\" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' " +
            "  AND \"release_date\"::date <= CURRENT_DATE " +
            "  AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "  AND COALESCE(" + q(ColumnMapper.EVENT_TYPE_COL) + ", '') IN ('', 'Normal') " +
            "ORDER BY \"release_date\" DESC, \"movie_name\" " +
            "LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Candidate(
                        rs.getString("movie_name"),
                        rs.getString("release_date"),
                        rs.getString("language")));
                }
            }
        }
        return result;
    }

    /**
     * Overwrites the classified event for exactly one row, but only when it's still classified
     * "Normal" or unclassified (NULL/blank) at the time of the write (guards against clobbering
     * a concurrent update, e.g. from the main scan pipeline). Used by the Normal-recheck path —
     * unlike {@link #updateEventIfMissing}, this DOES replace an existing "Normal" value, since
     * the whole point is correcting a stale one.
     */
    public void overwriteIfNormal(String movieName, String releaseDate, String language,
                                   String eventType, String eventName, String eventDetail,
                                   String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            q(ColumnMapper.EVENT_TYPE_COL)   + " = ?, " +
            q(ColumnMapper.EVENT_NAME_COL)   + " = ?, " +
            q(ColumnMapper.EVENT_DETAIL_COL) + " = ?, " +
            q(LAST_CHECKED_COL) + " = ? " +
            "WHERE \"movie_name\" = ? AND \"release_date\" = ? AND \"language\" = ? " +
            "  AND COALESCE(" + q(ColumnMapper.EVENT_TYPE_COL) + ", '') IN ('', 'Normal')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, eventName);
            ps.setString(3, eventDetail);
            ps.setString(4, checkedDate);
            ps.setString(5, movieName);
            ps.setString(6, releaseDate);
            ps.setString(7, language);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /** Records that this row was checked today but nothing was written (e.g. unresolvable country). */
    public void markChecked(String movieName, String releaseDate, String language, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET " + q(LAST_CHECKED_COL) + " = ? " +
            "WHERE \"movie_name\" = ? AND \"release_date\" = ? AND \"language\" = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, releaseDate);
            ps.setString(4, language);
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
