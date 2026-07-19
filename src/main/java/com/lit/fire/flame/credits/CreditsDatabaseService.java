package com.lit.fire.flame.credits;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the director/production-company enrichment service.
 * Mirrors SynopsisDatabaseService conventions: dedicated connection, ADD COLUMN IF NOT
 * EXISTS, update-by movie_name + release-year.
 */
public class CreditsDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public CreditsDatabaseService(String url, String user, String password,
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
     * Ensures "directors" and "production_companies" exist, along with
     * "credits_last_checked" — an internal bookkeeping column (not one of the requested
     * fields) that records the last date a movie was searched and found nowhere, so movies
     * sacnilk has no page (or no credits block) for aren't re-selected every single cycle
     * forever. Safe to call on every startup (uses ADD COLUMN IF NOT EXISTS).
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"directors\" TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"production_companies\" TEXT DEFAULT NULL");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"credits_last_checked\" TEXT");
        }
        connection.commit();
    }

    /** One (movie_name, year) group awaiting director/production-company enrichment. */
    public record Candidate(String movieName, String year) {}

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (SynopsisDatabaseService, CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    /**
     * Returns up to {@code limit} distinct (movie_name, year) groups still missing
     * "directors" and/or "production_companies", restricted to Indian-language movies with
     * release year strictly after 2000 and up to the current year, ordered most-recently-
     * released first.
     *
     * A group is skipped when every row missing either field was already checked within
     * the last {@code recheckDays} days — see markChecked().
     */
    public List<Candidate> getMoviesMissingCredits(int limit, int recheckDays) throws SQLException {
        String sql =
            "SELECT movie_name, LEFT(release_date, 4) AS yr " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            "   AND LEFT(release_date, 4)::int > 2000 " +
            "   AND LEFT(release_date, 4)::int <= EXTRACT(YEAR FROM CURRENT_DATE)::int " +
            "   AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "HAVING BOOL_OR((COALESCE(\"directors\", '') = '' OR COALESCE(\"production_companies\", '') = '') AND " +
            "               (\"credits_last_checked\" IS NULL OR \"credits_last_checked\"::date < CURRENT_DATE - ?::int)) " +
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
                    result.add(new Candidate(name, yr));
                }
            }
        }
        return result;
    }

    /** Records that this movie was searched today and still has a field missing. */
    public void markChecked(String movieName, String year, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"credits_last_checked\" = ?" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?" +
            "   AND (COALESCE(\"directors\", '') = '' OR COALESCE(\"production_companies\", '') = '')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, year);
            ps.executeUpdate();
        }
        connection.commit();
    }

    /**
     * Writes directors/production_companies for every row matching movieName + release
     * year, but only fills a field that's currently empty — so a higher-confidence value
     * already sitting in the table is never clobbered, and re-runs stay idempotent.
     * Either parameter may be null (source had nothing for that particular field).
     *
     * @return number of rows updated
     */
    public int updateCreditsIfMissing(String movieName, String year,
                                       String directors, String productionCompanies) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"directors\" = COALESCE(NULLIF(\"directors\", ''), ?), " +
            "     \"production_companies\" = COALESCE(NULLIF(\"production_companies\", ''), ?)" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?" +
            "   AND (COALESCE(\"directors\", '') = '' OR COALESCE(\"production_companies\", '') = '')";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, directors);
            ps.setString(2, productionCompanies);
            ps.setString(3, movieName);
            ps.setString(4, year);
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
