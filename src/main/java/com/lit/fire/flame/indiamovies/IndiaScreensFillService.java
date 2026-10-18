package com.lit.fire.flame.indiamovies;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * One-shot fill (--india-screens-fill-once) of india_movies_data_collection.number_of_screens for
 * rows that have none. Never overwrites a count, so it is safe to re-run.
 * <ol>
 *   <li>Box Office India, for rows whose first language is Hindi: every film page is read once into
 *       boi_movies (resumable; ~6,300 pages at one a second), then matched by title (or an
 *       alternate title after a comma) and a release date within {@value #BOI_MAX_DAYS_APART} days.
 *       BOI's count is the all-India opening screen count.</li>
 *   <li>English Wikipedia, for every row still empty: the article found by the row's IMDb id (year
 *       within one of the release year), read by ScreenCountExtractor. Worldwide-only figures are
 *       stored too, with scope "worldwide".</li>
 * </ol>
 * Where each count came from, its scope ("india", "unspecified", "state:kerala", "worldwide") and
 * the evidence (BOI url or Wikipedia sentence) go to india_screens_source, so state-level figures
 * can be told apart or cleared. The India table is touched only by single-row autocommit UPDATEs of
 * number_of_screens with a lock timeout, since other columns are being edited by hand meanwhile.
 */
public class IndiaScreensFillService {

    private static final String PREFIX = "[INDIA-SCREENS] ";
    private static final String TABLE = "india_movies_data_collection";
    private static final String BOI_TABLE = "boi_movies";
    private static final String SOURCE_TABLE = "india_screens_source";
    private static final int BOI_MAX_DAYS_APART = 7;
    private static final double BOI_MIN_TITLE_SIMILARITY = 0.8;
    private static final long BOI_PAUSE_MS = 1000;
    /** Ids beyond the highest one linked from the year lists that are still checked for newer films. */
    private static final int BOI_ID_MARGIN = 300;
    private static final int BOI_FIRST_LIST_YEAR = 2006;

    private record Row(String movieName, String releaseDate, String language, String imdbId) {
        LocalDate date() { return LocalDate.parse(releaseDate); }
        String firstLanguage() { return language == null ? "" : language.split(",")[0].trim().toLowerCase(Locale.ROOT); }
    }

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        try (Connection conn = DriverManager.getConnection(secrets.getProperty("db.url"),
                secrets.getProperty("db.user"), secrets.getProperty("db.password", ""))) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET lock_timeout = '5s'");
                st.execute("CREATE TABLE IF NOT EXISTS " + BOI_TABLE + " (movieid INTEGER PRIMARY KEY, title TEXT,"
                    + " release_date DATE, screens INTEGER, fetched_at TIMESTAMP NOT NULL DEFAULT now())");
                st.execute("CREATE TABLE IF NOT EXISTS " + SOURCE_TABLE + " (movie_name TEXT, release_date TEXT, language TEXT,"
                    + " number_of_screens INTEGER, stored BOOLEAN, source TEXT, scope TEXT, evidence TEXT,"
                    + " checked_at TIMESTAMP NOT NULL DEFAULT now(), PRIMARY KEY (movie_name, release_date, language))");
            }

            crawlBoxOfficeIndia(conn);
            int boi = fillFromBoxOfficeIndia(conn);
            log(String.format("Box Office India: filled %,d row(s).", boi));
            int wiki = fillFromWikipedia(conn);
            log(String.format("Wikipedia: filled %,d row(s).", wiki));
            logSummary(conn);
        }
    }

    // ---- Box Office India ----

    private void crawlBoxOfficeIndia(Connection conn) throws Exception {
        BoxOfficeIndiaClient client = new BoxOfficeIndiaClient();
        int maxId = client.highestListedId(BOI_FIRST_LIST_YEAR, LocalDate.now().getYear(), BOI_PAUSE_MS) + BOI_ID_MARGIN;
        Set<Integer> done = new HashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT movieid FROM " + BOI_TABLE)) {
            while (rs.next()) done.add(rs.getInt(1));
        }
        int todo = 0;
        for (int id = 1; id <= maxId; id++) if (!done.contains(id)) todo++;
        log(String.format("Box Office India: ids 1-%,d, %,d already read, %,d to read.", maxId, done.size(), todo));

        String sql = "INSERT INTO " + BOI_TABLE + " (movieid, title, release_date, screens) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (movieid) DO UPDATE SET title = EXCLUDED.title, release_date = EXCLUDED.release_date,"
            + " screens = EXCLUDED.screens, fetched_at = now()";
        int read = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int id = 1; id <= maxId; id++) {
                if (done.contains(id)) continue;
                Optional<BoxOfficeIndiaClient.Movie> movie;
                try {
                    movie = client.movie(id);
                } catch (Exception e) {
                    logErr("Box Office India id " + id + " skipped: " + e.getMessage());
                    Thread.sleep(BOI_PAUSE_MS);
                    continue;
                }
                ps.setInt(1, id);
                ps.setString(2, movie.map(BoxOfficeIndiaClient.Movie::title).orElse(null));
                ps.setDate(3, movie.map(m -> Date.valueOf(m.releaseDate())).orElse(null));
                Integer screens = movie.map(BoxOfficeIndiaClient.Movie::screens).orElse(null);
                if (screens == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, screens);
                ps.executeUpdate();
                if (++read % 250 == 0) log(String.format("Box Office India: read %,d / %,d.", read, todo));
                Thread.sleep(BOI_PAUSE_MS);
            }
        }
    }

    private int fillFromBoxOfficeIndia(Connection conn) throws SQLException {
        record BoiFilm(int id, String title, LocalDate date, int screens) {}
        List<BoiFilm> films = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT movieid, title, release_date, screens FROM " + BOI_TABLE + " WHERE screens > 0 AND release_date IS NOT NULL")) {
            while (rs.next()) films.add(new BoiFilm(rs.getInt(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getInt(4)));
        }
        int filled = 0;
        for (Row row : emptyRows(conn)) {
            if (!row.firstLanguage().equals("hindi")) continue;
            BoiFilm best = null;
            double bestScore = 0;
            for (BoiFilm f : films) {
                long days = Math.abs(ChronoUnit.DAYS.between(f.date(), row.date()));
                if (days > BOI_MAX_DAYS_APART) continue;
                double sim = titleSimilarity(row.movieName(), f.title());
                if (sim < BOI_MIN_TITLE_SIMILARITY) continue;
                double score = sim - days / 100.0;
                if (score > bestScore) { best = f; bestScore = score; }
            }
            if (best != null && store(conn, row, best.screens(), "boxofficeindia", "india",
                    "https://boxofficeindia.com/movie.php?movieid=" + best.id() + " (" + best.title() + ")")) {
                filled++;
            }
        }
        return filled;
    }

    /** Best similarity between the row's title (or an alternate after a comma) and a BOI title. */
    static double titleSimilarity(String rowTitle, String boiTitle) {
        String b = normalizeTitle(boiTitle);
        double best = 0;
        for (String alt : rowTitle.split(",\\s+")) {
            String a = normalizeTitle(alt);
            if (a.isEmpty() || b.isEmpty()) continue;
            if (a.equals(b)) return 1.0;
            best = Math.max(best, WikipediaMusicClient.similarity(a, b));
        }
        return best;
    }

    private static String normalizeTitle(String s) {
        return s.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }

    // ---- Wikipedia ----

    private int fillFromWikipedia(Connection conn) throws Exception {
        List<Row> rows = emptyRows(conn).stream().filter(r -> r.imdbId() != null && r.imdbId().startsWith("tt")).toList();
        log(String.format("Wikipedia: looking up %,d row(s).", rows.size()));
        Map<String, WikipediaArticleClient.Article> articles =
            new WikipediaArticleClient().articlesByImdbId(rows.stream().map(Row::imdbId).toList());
        int filled = 0;
        for (Row row : rows) {
            WikipediaArticleClient.Article article = articles.get(row.imdbId());
            if (article == null) continue;
            if (article.year() != null && Math.abs(article.year() - row.date().getYear()) > 1) continue;
            Optional<ScreenCountExtractor.Pick> pick =
                ScreenCountExtractor.pick(row.language(), ScreenCountExtractor.clean(article.wikitext()));
            if (pick.isEmpty()) continue;
            ScreenCountExtractor.Pick p = pick.get();
            String evidence = "https://en.wikipedia.org/wiki/" + article.title().replace(' ', '_') + " : " + p.sentence();
            if (store(conn, row, p.screens(), "wikipedia", p.scope(), evidence)) filled++;
        }
        return filled;
    }

    // ---- DB ----

    private List<Row> emptyRows(Connection conn) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT movie_name, release_date, language, id FROM " + TABLE + " WHERE number_of_screens IS NULL")) {
            while (rs.next()) rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return rows;
    }

    /** Sets the row's number_of_screens if it is still empty; false when it was not (or the row was locked). */
    private boolean store(Connection conn, Row row, int screens, String source, String scope, String evidence) throws SQLException {
        int updated;
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + TABLE + " SET number_of_screens = ?"
                + " WHERE movie_name = ? AND release_date = ? AND language = ? AND number_of_screens IS NULL")) {
            ps.setInt(1, screens);
            ps.setString(2, row.movieName());
            ps.setString(3, row.releaseDate());
            ps.setString(4, row.language());
            updated = ps.executeUpdate();
        } catch (SQLException e) {
            if (!"55P03".equals(e.getSQLState())) throw e;
            logErr("Skipped " + row.movieName() + " (" + row.releaseDate() + "): row locked by another session.");
            return false;
        }
        if (updated > 0) recordSource(conn, row, screens, true, source, scope, evidence);
        return updated > 0;
    }

    private void recordSource(Connection conn, Row row, int screens, boolean stored, String source, String scope,
                              String evidence) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + SOURCE_TABLE
                + " (movie_name, release_date, language, number_of_screens, stored, source, scope, evidence)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (movie_name, release_date, language) DO UPDATE SET"
                + " number_of_screens = EXCLUDED.number_of_screens, stored = EXCLUDED.stored, source = EXCLUDED.source,"
                + " scope = EXCLUDED.scope, evidence = EXCLUDED.evidence, checked_at = now()")) {
            ps.setString(1, row.movieName());
            ps.setString(2, row.releaseDate());
            ps.setString(3, row.language());
            ps.setInt(4, screens);
            ps.setBoolean(5, stored);
            ps.setString(6, source);
            ps.setString(7, scope);
            ps.setString(8, evidence);
            ps.executeUpdate();
        }
    }

    private void logSummary(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), count(number_of_screens) FROM " + TABLE)) {
                rs.next();
                log(String.format("number_of_screens now set on %,d of %,d row(s).", rs.getInt(2), rs.getInt(1)));
            }
            try (ResultSet rs = st.executeQuery("SELECT source, scope, stored, count(*) FROM " + SOURCE_TABLE
                    + " GROUP BY 1, 2, 3 ORDER BY 1, 3 DESC, 4 DESC")) {
                while (rs.next()) {
                    log(String.format("  %-15s %-28s %-10s %,d", rs.getString(1), rs.getString(2),
                        rs.getBoolean(3) ? "stored" : "not stored", rs.getInt(4)));
                }
            }
        }
    }

    private Properties loadProperties(String resourceName, boolean required) {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                if (required) throw new RuntimeException(resourceName + " not found on classpath");
                return props;
            }
            props.load(is);
        } catch (IOException e) {
            if (required) throw new RuntimeException("Cannot load " + resourceName, e);
        }
        return props;
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
    }

    private void logErr(String msg) {
        System.err.println(PREFIX + msg);
        System.err.flush();
    }
}
