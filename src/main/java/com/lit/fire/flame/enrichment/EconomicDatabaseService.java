package com.lit.fire.flame.enrichment;

import com.lit.fire.flame.mapper.ColumnMapper;

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
 * Database operations for the GDP/inflation backfill over existing rows in
 * movies_data_collection. Mirrors CreditsDatabaseService/CrawlerDatabaseService
 * conventions: dedicated connection, ADD COLUMN IF NOT EXISTS, update-by release year.
 */
public class EconomicDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public EconomicDatabaseService(String url, String user, String password,
                                    String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (CreditsDatabaseService, CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService).
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    public void ensureColumnsExist() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(ColumnMapper.GDP_COL) + " NUMERIC DEFAULT 0");
            stmt.execute("ALTER TABLE " + q(tableName) +
                " ADD COLUMN IF NOT EXISTS " + q(ColumnMapper.INFLATION_COL) + " NUMERIC DEFAULT 0");
        }
        connection.commit();
    }

    /**
     * Returns distinct release years, strictly after 2000 and up to the current year, for
     * Indian-language movies that still need GDP and/or inflation filled in (column is NULL
     * or the NUMERIC default of 0), most recent year first — so a re-run after a partial
     * failure skips years already fully filled instead of re-hitting the World Bank API.
     */
    public List<Integer> getYearsNeedingEnrichment() throws SQLException {
        String sql =
            "SELECT DISTINCT LEFT(release_date, 4)::int AS yr " +
            "FROM " + q(tableName) +
            " WHERE release_date IS NOT NULL AND LEFT(release_date, 4) ~ '^[0-9]{4}$' " +
            "   AND LEFT(release_date, 4)::int > 2000 " +
            "   AND LEFT(release_date, 4)::int <= EXTRACT(YEAR FROM CURRENT_DATE)::int " +
            "   AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL + " " +
            "   AND (COALESCE(" + q(ColumnMapper.GDP_COL) + ", 0) = 0 " +
            "     OR COALESCE(" + q(ColumnMapper.INFLATION_COL) + ", 0) = 0) " +
            "ORDER BY yr DESC";
        List<Integer> years = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) years.add(rs.getInt("yr"));
        }
        return years;
    }

    /** Counts Indian-language movie rows for a given release year (for progress reporting). */
    public int countIndianMoviesForYear(int year) throws SQLException {
        String sql =
            "SELECT COUNT(*) FROM " + q(tableName) +
            " WHERE LEFT(release_date, 4) = ? AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(year));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Writes GDP/inflation for every Indian-language row with that release year still
     * missing a value (NULL or the NUMERIC default 0). Safe to call with a null gdp/inflation
     * (World Bank had no data for that year) — the corresponding column is simply left alone.
     *
     * @return number of rows updated
     */
    public int updateYearIfMissing(int year, Double gdp, Double inflation) throws SQLException {
        if (gdp == null && inflation == null) return 0;

        String sql = "UPDATE " + q(tableName) + " SET " +
            q(ColumnMapper.GDP_COL) + " = CASE WHEN ? IS NOT NULL AND COALESCE(" + q(ColumnMapper.GDP_COL) + ", 0) = 0 " +
            "THEN ? ELSE " + q(ColumnMapper.GDP_COL) + " END, " +
            q(ColumnMapper.INFLATION_COL) + " = CASE WHEN ? IS NOT NULL AND COALESCE(" + q(ColumnMapper.INFLATION_COL) + ", 0) = 0 " +
            "THEN ? ELSE " + q(ColumnMapper.INFLATION_COL) + " END " +
            "WHERE LEFT(\"release_date\", 4) = ? AND LOWER(\"language\") IN " + INDIAN_LANGUAGES_SQL;

        int updated;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (Double v : new Double[]{gdp, gdp, inflation, inflation}) {
                if (v == null) ps.setNull(i++, Types.NUMERIC);
                else ps.setDouble(i++, v);
            }
            ps.setString(i, String.valueOf(year));
            updated = ps.executeUpdate();
        }
        connection.commit();
        return updated;
    }

    private String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

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
