package com.lit.fire.flame.indiamovies;

import com.lit.fire.flame.moviedetails.IndiaMovieRecord;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Database operations for the india_movies_data_collection catalog import (see
 * IndiaMoviesImportService). The target table is created as an exact schema mirror of the
 * source table (movies_data_collection by default) — same columns, same primary key
 * (movie_name, release_date, language), same indexes — via {@code LIKE ... INCLUDING ALL}, so
 * every other service in this app that takes a {@code table.name} override could point at it
 * later without any code change, even though this import only ever populates a subset of its
 * columns (rating_10, runtime, genre, country, budget, revenue, production_companies,
 * directors, cbfc_rating — everything else, e.g. youtube/GDP/marketing-tactics columns, stays
 * NULL/default until some other service is pointed at this table).
 *
 * Unlike movies_data_collection's other backfill services (which only ever fill a currently-
 * empty field, never overwriting a value from another source), this table has exactly one
 * writer — this import — so a re-run intentionally overwrites every targeted column with
 * TMDB's latest answer (INSERT ... ON CONFLICT ... DO UPDATE), not just fills gaps.
 */
public class IndiaMoviesDatabaseService implements AutoCloseable {

    private static final String LAST_CHECKED_COL = "movie_details_last_checked";

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (CreditsDatabaseService, CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService,
     * EconomicDatabaseService, MarketingTacticsDatabaseService, ReleaseEventDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    private final Connection connection;
    private final String sourceTable;
    private final String targetTable;

    public IndiaMoviesDatabaseService(String url, String user, String password,
                                       String sourceTable, String targetTable) throws SQLException {
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.connection  = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    public boolean tableExists(String tableName) throws SQLException {
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
     * Creates the target table as an exact schema mirror of the source table (columns, defaults,
     * NOT NULL constraints, primary key, and every other index) if it doesn't already exist.
     * Safe to call on every startup — a no-op once the table exists.
     */
    public void ensureTableExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + q(targetTable) +
                " (LIKE " + q(sourceTable) + " INCLUDING ALL)");
        }
        connection.commit();
    }

    /**
     * Inserts a new row, or overwrites the TMDB-sourced columns of an existing row matching the
     * same (movie_name, release_date, language) primary key, and stamps the bookkeeping column
     * with today's date either way.
     */
    public void upsert(IndiaMovieRecord rec, String checkedDate) throws SQLException {
        String sql = "INSERT INTO " + q(targetTable) + " " +
            "(\"movie_name\", \"release_date\", \"language\", \"rating_10\", \"runtime\", \"genre\", " +
            " \"country\", \"budget\", \"revenue\", \"production_companies\", \"directors\", " +
            " \"cbfc_rating\", " + q(LAST_CHECKED_COL) + ") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (\"movie_name\", \"release_date\", \"language\") DO UPDATE SET " +
            "\"rating_10\" = EXCLUDED.\"rating_10\", " +
            "\"runtime\" = EXCLUDED.\"runtime\", " +
            "\"genre\" = EXCLUDED.\"genre\", " +
            "\"country\" = EXCLUDED.\"country\", " +
            "\"budget\" = EXCLUDED.\"budget\", " +
            "\"revenue\" = EXCLUDED.\"revenue\", " +
            "\"production_companies\" = EXCLUDED.\"production_companies\", " +
            "\"directors\" = EXCLUDED.\"directors\", " +
            "\"cbfc_rating\" = EXCLUDED.\"cbfc_rating\", " +
            q(LAST_CHECKED_COL) + " = EXCLUDED." + q(LAST_CHECKED_COL);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, rec.movieName());
            ps.setString(i++, rec.releaseDate());
            ps.setString(i++, rec.language());
            setNumericOrNull(ps, i++, rec.rating());
            setNumericOrNull(ps, i++, rec.runtimeMinutes());
            ps.setString(i++, rec.genre());
            ps.setString(i++, rec.country());
            setNumericOrNull(ps, i++, rec.budget());
            setNumericOrNull(ps, i++, rec.revenue());
            ps.setString(i++, rec.productionCompanies());
            ps.setString(i++, rec.directors());
            ps.setString(i++, rec.cbfcRating());
            ps.setString(i, checkedDate);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Copies every Indian-language row from the source table straight into the target table
     * (see {@code IndiaMoviesCopyService}) — full row, every already-filled column, not just the
     * TMDB-shaped subset {@link #upsert} writes — for movies whose (trimmed, case-insensitive)
     * movie_name isn't already present in the target table and whose budget AND revenue are
     * both non-zero. A plain {@code INSERT ... SELECT s.*} is valid here because the target
     * table is an exact {@code LIKE ... INCLUDING ALL} mirror of the source (identical columns,
     * same order — see {@link #ensureTableExists}). {@code ON CONFLICT DO NOTHING} is a defensive
     * no-op in the normal case (the NOT EXISTS clause already excludes movie_names on file), kept
     * only as a safety net against a same-PK race.
     *
     * @return the number of rows actually inserted
     */
    public int copyExistingIndianLanguageMovies() throws SQLException {
        String sql = "INSERT INTO " + q(targetTable) + " " +
            "SELECT s.* FROM " + q(sourceTable) + " s " +
            "WHERE LOWER(s.\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "  AND s.\"budget\" > 0 AND s.\"revenue\" > 0 " +
            "  AND NOT EXISTS (" +
            "    SELECT 1 FROM " + q(targetTable) + " d " +
            "    WHERE LOWER(TRIM(d.\"movie_name\")) = LOWER(TRIM(s.\"movie_name\"))" +
            "  ) " +
            "ON CONFLICT (\"movie_name\", \"release_date\", \"language\") DO NOTHING";
        int inserted;
        try (Statement stmt = connection.createStatement()) {
            inserted = stmt.executeUpdate(sql);
        }
        connection.commit();
        return inserted;
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
