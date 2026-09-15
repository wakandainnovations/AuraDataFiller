package com.lit.fire.flame.kannadabudget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Database operations for the mtwikiblog.com Kannada-territory budget/revenue backfill (see
 * KannadaBudgetBackfillService). PK is (movie_name, release_date, language) — see
 * DatabaseService.ensureTableExists() — so relabeling a row's language ('unknown' -> 'kannada')
 * is a normal UPDATE of one PK column, not a delete+insert.
 *
 * Matching is by movie_name only (case-insensitive/trimmed) — the source site buckets rows by
 * an approximate year, not a real release_date, so there is nothing more precise to match on.
 */
public class KannadaBudgetDatabaseService implements AutoCloseable {

    private final Connection connection;
    private final String tableName;

    public KannadaBudgetDatabaseService(String url, String user, String password,
                                         String tableName) throws SQLException {
        this.tableName  = tableName;
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    public record ExistingRow(String movieName, String releaseDate, String language) {}

    /** Every row (any language) whose movie_name matches, case-insensitive/trimmed. */
    public List<ExistingRow> findByName(String movieName) throws SQLException {
        String sql = "SELECT movie_name, release_date, language FROM " + q(tableName) +
            " WHERE lower(trim(movie_name)) = lower(trim(?))";
        List<ExistingRow> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ExistingRow(
                        rs.getString("movie_name"), rs.getString("release_date"), rs.getString("language")));
                }
            }
        }
        return result;
    }

    /**
     * Fills budget/revenue on one exact row, but only a field currently 0/NULL — never
     * clobbers a better value already present. When {@code relabelToKannada} is true (the row's
     * current language is 'unknown'), also sets language = 'kannada'.
     *
     * @return number of rows updated (0 if the field(s) were already populated and no relabel
     *         was requested, or if relabeling collided with an existing (movie_name,
     *         release_date, 'kannada') row's primary key)
     */
    public int fillFinanceAndMaybeRelabel(String movieName, String releaseDate, String currentLanguage,
                                           Long budgetUsd, Long revenueUsd, boolean relabelToKannada) throws SQLException {
        String sql = "UPDATE " + q(tableName) + " SET " +
            "language = CASE WHEN ? THEN 'kannada' ELSE language END, " +
            "budget  = CASE WHEN COALESCE(budget, 0)  = 0 AND ? IS NOT NULL THEN ? ELSE budget  END, " +
            "revenue = CASE WHEN COALESCE(revenue, 0) = 0 AND ? IS NOT NULL THEN ? ELSE revenue END " +
            "WHERE movie_name = ? AND release_date = ? AND language = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setBoolean(i++, relabelToKannada);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, budgetUsd);
            setLongOrNull(ps, i++, revenueUsd);
            setLongOrNull(ps, i++, revenueUsd);
            ps.setString(i++, movieName);
            ps.setString(i++, releaseDate);
            ps.setString(i,   currentLanguage);
            int updated = ps.executeUpdate();
            connection.commit();
            return updated;
        }
    }

    /**
     * Inserts a brand-new row with language = 'kannada'. Only budget/revenue are set beyond the
     * PK columns — every other column keeps its normal table default.
     *
     * @return false if a row with this exact (movie_name, release_date, 'kannada') already
     *         exists (e.g. the same movie appears twice on the source page) — no-op in that case.
     */
    public boolean insertNewRow(String movieName, String releaseDate,
                                 Long budgetUsd, Long revenueUsd) throws SQLException {
        String sql = "INSERT INTO " + q(tableName) +
            " (movie_name, release_date, language, budget, revenue) VALUES (?, ?, 'kannada', ?, ?) " +
            "ON CONFLICT (movie_name, release_date, language) DO NOTHING";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, releaseDate);
            setLongOrNull(ps, 3, budgetUsd);
            setLongOrNull(ps, 4, revenueUsd);
            int rows = ps.executeUpdate();
            connection.commit();
            return rows > 0;
        }
    }

    private void setLongOrNull(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value != null) ps.setLong(idx, value);
        else ps.setNull(idx, Types.NUMERIC);
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
