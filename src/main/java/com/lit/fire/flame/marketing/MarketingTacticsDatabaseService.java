package com.lit.fire.flame.marketing;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Database operations for the marketing-tactics enrichment service.
 *
 * Owns two tables:
 *   - movie_marketing_tactics: one row per (movie, sub-classification) actually used, with the
 *     tactic detail strings AuraLLM returned for it. This is the table future callers query for
 *     retrieval (movie_name, release_year, language, sub_classification_number is the PK, so a
 *     lookup by movie_name+release_year+language is a single index scan).
 *   - movie_marketing_tactics_scan_status: one row per movie ever sent to AuraLLM, independent of
 *     whether any tactic was found — bookkeeping so a movie AuraLLM genuinely has nothing to say
 *     about isn't resubmitted every single cycle (mirrors credits_last_checked /
 *     runtime_budget_last_checked elsewhere in this app, just as its own table since the data shape
 *     here is one-row-per-movie rather than a column bolted onto movies_data_collection, which
 *     wouldn't work anyway since candidates are also sourced from managed_entities).
 */
public class MarketingTacticsDatabaseService implements AutoCloseable {

    public static final String TACTICS_TABLE      = "movie_marketing_tactics";
    public static final String SCAN_STATUS_TABLE   = "movie_marketing_tactics_scan_status";

    /**
     * Indian-language filter, matching the same list used elsewhere in the app
     * (CreditsDatabaseService, EconomicDatabaseService,
     * CrawlerDatabaseService.queryMissingBoxOffice, YoutubeDatabaseService). Used here to give
     * Indian-language and Hollywood candidates scan priority over everything else, per the
     * product owner's request — see {@link #getCandidates}.
     */
    private static final String INDIAN_LANGUAGES_SQL =
        "('hindi','tamil','telugu','malayalam','kannada','bengali'," +
        "'marathi','punjabi','gujarati','odia','oriya','urdu'," +
        "'assamese','bhojpuri','nepali','rajasthani','rajastani'," +
        "'tulu','sanskrit','konkani','kashmiri')";

    private final Connection connection;

    public MarketingTacticsDatabaseService(String url, String user, String password) throws SQLException {
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    /** Creates both tables if they don't already exist. Safe to call on every startup. */
    public void ensureSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + q(TACTICS_TABLE) + " (" +
                "  \"movie_name\"                 TEXT NOT NULL, " +
                "  \"release_year\"                TEXT NOT NULL, " +
                "  \"language\"                    TEXT NOT NULL, " +
                "  \"main_classification_number\"  SMALLINT NOT NULL, " +
                "  \"main_classification_name\"    TEXT NOT NULL, " +
                "  \"sub_classification_number\"   SMALLINT NOT NULL, " +
                "  \"sub_classification_name\"     TEXT NOT NULL, " +
                "  \"tactic_details\"               TEXT[] NOT NULL, " +
                "  \"updated_at\"                  TIMESTAMP NOT NULL DEFAULT now(), " +
                "  PRIMARY KEY (\"movie_name\", \"release_year\", \"language\", \"sub_classification_number\")" +
                ")");
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + q(SCAN_STATUS_TABLE) + " (" +
                "  \"movie_name\"          TEXT NOT NULL, " +
                "  \"release_year\"        TEXT NOT NULL, " +
                "  \"language\"            TEXT NOT NULL, " +
                "  \"last_scanned_at\"     TIMESTAMP NOT NULL DEFAULT now(), " +
                "  \"tactics_found_count\" INT NOT NULL DEFAULT 0, " +
                "  PRIMARY KEY (\"movie_name\", \"release_year\", \"language\")" +
                ")");
        }
        connection.commit();
    }

    /** Returns true when {@code tableName} exists in the public schema. */
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

    /** One movie awaiting (or due for re-) marketing-tactics classification. */
    public record Candidate(String movieName, String year, String language) {}

    /**
     * Returns up to {@code limit} candidates spanning {@code moviesTable} (release_date TEXT,
     * as elsewhere in this app) and, when present, managed_entities (type='MOVIE', release_date
     * DATE) — both restricted to already-released movies with release year in
     * [currentYear - lookbackYears, currentYear], most-recently-released first. A candidate is
     * skipped when it was already scanned within the last {@code recheckDays} days.
     */
    public List<Candidate> getCandidates(String moviesTable, boolean includeManagedEntities,
                                          int lookbackYears, int recheckDays, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("WITH candidates AS (")
           .append("  SELECT \"movie_name\" AS movie_name, LEFT(\"release_date\", 4) AS yr, ")
           .append("         COALESCE(NULLIF(\"language\", ''), 'Unknown') AS language, \"country\" AS country ")
           .append("  FROM ").append(q(moviesTable)).append(' ')
           .append("  WHERE \"release_date\" IS NOT NULL AND LEFT(\"release_date\", 4) ~ '^[0-9]{4}$' ")
           .append("    AND LEFT(\"release_date\", 4)::int BETWEEN ? AND EXTRACT(YEAR FROM CURRENT_DATE)::int ")
           .append("    AND (LENGTH(\"release_date\") <> 10 OR \"release_date\"::date <= CURRENT_DATE) ");
        if (includeManagedEntities) {
            sql.append("  UNION ")
               .append("  SELECT \"name\" AS movie_name, TO_CHAR(\"release_date\", 'YYYY') AS yr, ")
               .append("         COALESCE(NULLIF(\"language\", ''), 'Unknown') AS language, NULL::text AS country ")
               .append("  FROM managed_entities ")
               .append("  WHERE \"type\" = 'MOVIE' AND \"release_date\" IS NOT NULL AND \"release_date\" <= CURRENT_DATE ")
               .append("    AND EXTRACT(YEAR FROM \"release_date\")::int BETWEEN ? AND EXTRACT(YEAR FROM CURRENT_DATE)::int ");
        }
        // Priority tiers requested by the product owner: Indian-language movies and Hollywood
        // (English-language, US-produced) movies first, then everything else — latest-to-oldest
        // release date preserved within each tier. "Hollywood" is approximated as
        // language=english AND country mentions the United States, since that's the only
        // country-level signal movies_data_collection carries (no separate "industry" field).
        sql.append(") ")
           .append("SELECT c.movie_name, c.yr, c.language FROM candidates c ")
           .append("LEFT JOIN ").append(q(SCAN_STATUS_TABLE)).append(" s ")
           .append("  ON s.\"movie_name\" = c.movie_name AND s.\"release_year\" = c.yr AND s.\"language\" = c.language ")
           .append("WHERE s.\"movie_name\" IS NULL OR s.\"last_scanned_at\" < CURRENT_DATE - ?::int ")
           .append("ORDER BY ")
           .append("  CASE ")
           .append("    WHEN LOWER(c.language) IN ").append(INDIAN_LANGUAGES_SQL).append(" THEN 0 ")
           .append("    WHEN LOWER(c.language) = 'english' AND LOWER(COALESCE(c.country, '')) LIKE '%united states%' THEN 1 ")
           .append("    ELSE 2 ")
           .append("  END, ")
           .append("  c.yr DESC, c.movie_name ")
           .append("LIMIT ?");

        List<Candidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            int cutoffYear = java.time.Year.now().getValue() - lookbackYears;
            ps.setInt(i++, cutoffYear);
            if (includeManagedEntities) ps.setInt(i++, cutoffYear);
            ps.setInt(i++, recheckDays);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("movie_name");
                    String yr   = rs.getString("yr");
                    String lang = rs.getString("language");
                    if (name == null || yr == null || !yr.matches("\\d{4}")) continue;
                    result.add(new Candidate(name, yr, lang));
                }
            }
        }
        return result;
    }

    /**
     * Replaces every tactic row for (movieName, year, language) with {@code tactics}
     * (subClassificationNumber -> non-empty detail strings) and records the scan in
     * movie_marketing_tactics_scan_status. Runs as one transaction — delete, re-insert, mark
     * scanned, single commit — so a re-scan is idempotent and a movie never ends up with a mix
     * of stale and fresh classification rows.
     */
    public void replaceTactics(String movieName, String year, String language,
                                Map<Integer, List<String>> tactics) throws SQLException {
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM " + q(TACTICS_TABLE) +
                " WHERE \"movie_name\" = ? AND \"release_year\" = ? AND \"language\" = ?")) {
            del.setString(1, movieName);
            del.setString(2, year);
            del.setString(3, language);
            del.executeUpdate();
        }

        if (!tactics.isEmpty()) {
            String insertSql = "INSERT INTO " + q(TACTICS_TABLE) + " " +
                "(\"movie_name\", \"release_year\", \"language\", " +
                " \"main_classification_number\", \"main_classification_name\", " +
                " \"sub_classification_number\", \"sub_classification_name\", \"tactic_details\", \"updated_at\") " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())";
            try (PreparedStatement ins = connection.prepareStatement(insertSql)) {
                for (Map.Entry<Integer, List<String>> entry : tactics.entrySet()) {
                    MarketingTacticTaxonomy.Tactic t = MarketingTacticTaxonomy.bySubNumber(entry.getKey());
                    if (t == null) continue;
                    Array detailsArray = connection.createArrayOf("text", entry.getValue().toArray());
                    ins.setString(1, movieName);
                    ins.setString(2, year);
                    ins.setString(3, language);
                    ins.setInt(4, t.mainNumber());
                    ins.setString(5, t.mainName());
                    ins.setInt(6, t.subNumber());
                    ins.setString(7, t.subName());
                    ins.setArray(8, detailsArray);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }

        upsertScanStatus(movieName, year, language, tactics.size());
        connection.commit();
    }

    /** Records a scan attempt with zero tactics found (parse succeeded, LLM had nothing). */
    public void markScannedNoData(String movieName, String year, String language) throws SQLException {
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM " + q(TACTICS_TABLE) +
                " WHERE \"movie_name\" = ? AND \"release_year\" = ? AND \"language\" = ?")) {
            del.setString(1, movieName);
            del.setString(2, year);
            del.setString(3, language);
            del.executeUpdate();
        }
        upsertScanStatus(movieName, year, language, 0);
        connection.commit();
    }

    private void upsertScanStatus(String movieName, String year, String language, int tacticsFound) throws SQLException {
        String sql = "INSERT INTO " + q(SCAN_STATUS_TABLE) +
            " (\"movie_name\", \"release_year\", \"language\", \"last_scanned_at\", \"tactics_found_count\") " +
            "VALUES (?, ?, ?, now(), ?) " +
            "ON CONFLICT (\"movie_name\", \"release_year\", \"language\") DO UPDATE SET " +
            "  \"last_scanned_at\" = now(), \"tactics_found_count\" = EXCLUDED.\"tactics_found_count\"";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, year);
            ps.setString(3, language);
            ps.setInt(4, tacticsFound);
            ps.executeUpdate();
        }
    }

    /** One classified tactic row, as returned by {@link #getTactics}. */
    public record TacticRecord(int mainClassificationNumber, String mainClassificationName,
                                int subClassificationNumber, String subClassificationName,
                                List<String> tacticDetails) {}

    /**
     * Retrieval entry point: every marketing tactic on file for one (movieName, language,
     * releaseYear), ordered by sub-classification number. This is the "single API call" lookup
     * the data feeds — movie_name+release_year+language is the leftmost prefix of the table's
     * primary key, so this is a single index scan.
     */
    public List<TacticRecord> getTactics(String movieName, String language, String releaseYear) throws SQLException {
        String sql = "SELECT \"main_classification_number\", \"main_classification_name\", " +
            "       \"sub_classification_number\", \"sub_classification_name\", \"tactic_details\" " +
            "FROM " + q(TACTICS_TABLE) + " " +
            "WHERE \"movie_name\" = ? AND \"release_year\" = ? AND LOWER(\"language\") = LOWER(?) " +
            "ORDER BY \"sub_classification_number\"";
        List<TacticRecord> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, releaseYear);
            ps.setString(3, language);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Array arr = rs.getArray("tactic_details");
                    List<String> details = new ArrayList<>();
                    if (arr != null) {
                        for (Object o : (Object[]) arr.getArray()) details.add((String) o);
                    }
                    result.add(new TacticRecord(
                        rs.getInt("main_classification_number"),
                        rs.getString("main_classification_name"),
                        rs.getInt("sub_classification_number"),
                        rs.getString("sub_classification_name"),
                        details));
                }
            }
        }
        return result;
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
