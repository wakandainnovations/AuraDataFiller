package com.lit.fire.flame.languageid;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the AuraLLM-driven language identification backfill (see
 * LanguageIdService). movies_data_collection is a general worldwide movie catalog, not
 * Indian-cinema-specific, so candidates are every row whose language is currently 'unknown' —
 * no language/region pre-filter.
 *
 * PK is (movie_name, release_date, language) — see DatabaseService.ensureTableExists() — so
 * resolving a row's language is a normal UPDATE of one PK column, not a delete+insert.
 *
 * language_id_last_checked is bookkeeping only (not one of the requested fields): it records the
 * last date a row was asked about and AuraLLM couldn't give a usable answer, so those rows aren't
 * re-selected every single cycle forever. A row resolved to a real language is never selected
 * again (it no longer matches language = 'unknown'), so no bookkeeping is needed for successes.
 */
public class LanguageIdDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public LanguageIdDatabaseService(String url, String user, String password,
                                      String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /** Safe to call on every startup (uses ADD COLUMN IF NOT EXISTS). */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"language_id_last_checked\" TEXT");
        }
        connection.commit();
    }

    public record Candidate(String movieName, String releaseDate) {}

    /**
     * Returns up to {@code limit} rows whose language is currently 'unknown', skipping any row
     * checked within the last {@code recheckDays} days (see markChecked()). Ordered oldest
     * release first — deliberately the opposite of this app's usual "most recent first"
     * convention: AuraLLM resolves older/established titles far more reliably than very
     * recent or upcoming ones (measured ~90% vs. ~0% on a sample), so oldest-first gets much
     * more of the backlog actually resolved per cycle instead of repeatedly re-querying titles
     * the model has no information about yet.
     */
    public List<Candidate> getUnknownLanguageMovies(int limit, int recheckDays) throws SQLException {
        String sql = "SELECT movie_name, release_date FROM " + q(tableName) +
            " WHERE lower(trim(language)) = 'unknown' " +
            "   AND (\"language_id_last_checked\" IS NULL OR \"language_id_last_checked\"::date < CURRENT_DATE - ?::int) " +
            " ORDER BY release_date ASC NULLS LAST " +
            " LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, recheckDays);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Candidate(rs.getString("movie_name"), rs.getString("release_date")));
                }
            }
        }
        return result;
    }

    /**
     * Sets language for one exact currently-'unknown' row.
     *
     * @return number of rows updated (0 if the row no longer matches, e.g. already resolved by
     *         a concurrent run)
     */
    public int resolveLanguage(String movieName, String releaseDate, String newLanguage) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET language = ? " +
            "WHERE movie_name = ? AND release_date = ? AND language = 'unknown'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newLanguage);
            ps.setString(2, movieName);
            ps.setString(3, releaseDate);
            int updated = ps.executeUpdate();
            connection.commit();
            return updated;
        }
    }

    /** Records that this row was checked today and AuraLLM couldn't resolve it. */
    public void markChecked(String movieName, String releaseDate, String checkedDate) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"language_id_last_checked\" = ? " +
            "WHERE movie_name = ? AND release_date = ? AND language = 'unknown'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checkedDate);
            ps.setString(2, movieName);
            ps.setString(3, releaseDate);
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
