package com.lit.fire.flame.crawler;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the Sacnilk box-office crawler.
 * Keeps a dedicated connection separate from the CSV-import path.
 */
public class CrawlerDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public CrawlerDatabaseService(String url, String user, String password,
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
     * Adds the revenue and budget columns when they are not already present.
     * Safe to call on every startup (uses ADD COLUMN IF NOT EXISTS).
     */
    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"revenue\" NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS \"budget\" NUMERIC DEFAULT 0");
        }
        connection.commit();
    }

    /**
     * Returns all distinct (movie_name, release_year) pairs stored in the table.
     * Each element is a String[3]: {movie_name, 4-digit-year, earliest_release_date}.
     * The earliest release_date within the year is included so callers can look up
     * the historical exchange rate for that specific date.
     */
    public List<String[]> getAllMovieNameYears() throws SQLException {
        List<String[]> result = new ArrayList<>();
        String sql = "SELECT movie_name, LEFT(release_date, 4) AS yr, MIN(release_date) AS release_date " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LENGTH(release_date) >= 4 " +
            "GROUP BY movie_name, LEFT(release_date, 4) " +
            "ORDER BY yr, movie_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name        = rs.getString("movie_name");
                String yr          = rs.getString("yr");
                String releaseDate = rs.getString("release_date");
                if (name != null && yr != null && yr.matches("\\d{4}")) {
                    result.add(new String[]{name, yr, releaseDate});
                }
            }
        }
        return result;
    }

    /**
     * Updates revenue and/or budget (both in full USD) for every row whose
     * movie_name matches and whose release_date starts with the given year.
     *
     * Values are already converted from INR Crore to whole USD by the caller.
     * Only non-null values are written; the other column is left unchanged via COALESCE.
     *
     * @return number of rows updated
     */
    public int updateBoxOffice(String movieName, String year,
                                Long revenueUsd, Long budgetUsd) throws SQLException {
        String sql = "UPDATE " + q(tableName) +
            " SET \"revenue\" = COALESCE(?, \"revenue\"), \"budget\" = COALESCE(?, \"budget\")" +
            " WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, revenueUsd);
            ps.setObject(2, budgetUsd);
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

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
