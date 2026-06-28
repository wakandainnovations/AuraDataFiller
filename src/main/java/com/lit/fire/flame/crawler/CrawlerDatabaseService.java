package com.lit.fire.flame.crawler;

import java.math.BigDecimal;
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
     * Each element is a String[2]: {movie_name, 4-digit-year}.
     */
    public List<String[]> getAllMovieNameYears() throws SQLException {
        List<String[]> result = new ArrayList<>();
        String sql = "SELECT DISTINCT movie_name, LEFT(release_date, 4) AS yr " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LENGTH(release_date) >= 4 " +
            "ORDER BY yr, movie_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("movie_name");
                String yr   = rs.getString("yr");
                if (name != null && yr != null && yr.matches("\\d{4}")) {
                    result.add(new String[]{name, yr});
                }
            }
        }
        return result;
    }

    /**
     * Updates revenue and/or budget for every row whose movie_name matches
     * and whose release_date starts with the given year.
     *
     * Only non-null values are written; the other column is left unchanged.
     *
     * @return number of rows updated (based on revenue update; budget update may be higher/lower)
     */
    public int updateBoxOffice(String movieName, String year,
                                Double worldwideCr, Double budgetCr) throws SQLException {
        int updated = 0;

        if (worldwideCr != null) {
            String sql = "UPDATE " + q(tableName) + " SET \"revenue\" = ? " +
                "WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBigDecimal(1, BigDecimal.valueOf(worldwideCr));
                ps.setString(2, movieName);
                ps.setString(3, year);
                updated = ps.executeUpdate();
            }
        }

        if (budgetCr != null) {
            String sql = "UPDATE " + q(tableName) + " SET \"budget\" = ? " +
                "WHERE \"movie_name\" = ? AND LEFT(\"release_date\", 4) = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBigDecimal(1, BigDecimal.valueOf(budgetCr));
                ps.setString(2, movieName);
                ps.setString(3, year);
                ps.executeUpdate();
            }
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
