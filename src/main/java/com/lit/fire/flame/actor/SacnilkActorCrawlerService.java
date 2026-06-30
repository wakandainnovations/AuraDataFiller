package com.lit.fire.flame.actor;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * Background service that crawls sacnilk.com for actor filmographies and upserts
 * results into the actors_data_collection table.
 *
 * Crawl policy (sacnilk.com/robots.txt):
 *   All pages allowed; Crawl-delay: 1 s.
 *   This service floors the delay at 1 500 ms to stay polite.
 *
 * Lifecycle:
 *   Started as a daemon thread by ActorDataCollectionService when it begins.
 *   Runs one full crawl cycle then sleeps for the configured interval (default 24 h).
 *
 * Cycle phases:
 *   1. Read robots.txt → determine crawl-delay.
 *   2. Discover actor filmography URLs from sacnilk sitemaps.
 *   3. Load distinct actor names from actors_data_collection.
 *   4. Fuzzy-match DB actors to sacnilk URL slugs (Levenshtein ratio ≥ threshold).
 *   5. Fetch each matched filmography page (respecting crawl-delay).
 *   6. Upsert post-1980 movies into actors_data_collection.
 *
 * Uniqueness: uses the existing PK (actor_name, movie_name, release_date) for
 * ON CONFLICT; existing column values are preferred over sacnilk values so that
 * manually curated CSV data is never overwritten.
 *
 * New columns added automatically if absent: status, sacnilk_url.
 */
public class SacnilkActorCrawlerService implements Runnable {

    private static final String PREFIX     = "[ACTOR-CRAWLER] ";
    private static final String TABLE_NAME = "actors_data_collection";
    private static final long   MIN_DELAY_MS = 1_500L;

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("actor.crawler.enabled", "true"))) {
            log("Disabled via actor.crawler.enabled=false — exiting.");
            return;
        }

        long   initialDelayMs = Long.parseLong(  config.getProperty("actor.crawler.initial.delay.ms", "30000"));
        long   intervalMs     = Long.parseLong(  config.getProperty("actor.crawler.interval.hours",   "24")) * 3_600_000L;
        double threshold      = Double.parseDouble(config.getProperty("actor.crawler.match.threshold", "0.75"));

        String dbUrl  = secrets.getProperty("db.url");
        String dbUser = secrets.getProperty("db.user");
        String dbPass = secrets.getProperty("db.password", "");

        sleep(initialDelayMs, "initial startup delay");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                log("=== Starting sacnilk actor filmography crawl cycle ===");
                runCrawlCycle(dbUrl, dbUser, dbPass, threshold);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logErr("Cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            log(String.format("=== Cycle complete. Next run in %d hour(s). ===", intervalMs / 3_600_000L));
            if (!sleep(intervalMs, "inter-cycle sleep")) break;
        }
        log("Service stopped.");
    }

    /**
     * Runs exactly one crawl cycle synchronously, then returns.
     * Intended for the {@code --actor-crawl} CLI mode where the JVM exits after completion.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        double threshold = Double.parseDouble(config.getProperty("actor.crawler.match.threshold", "0.75"));
        String dbUrl  = secrets.getProperty("db.url");
        String dbUser = secrets.getProperty("db.user");
        String dbPass = secrets.getProperty("db.password", "");
        log("=== Starting sacnilk actor filmography crawl cycle (one-shot) ===");
        runCrawlCycle(dbUrl, dbUser, dbPass, threshold);
    }

    // -------------------------------------------------------------------------
    // Crawl cycle
    // -------------------------------------------------------------------------

    private void runCrawlCycle(String dbUrl, String dbUser, String dbPass,
                                double threshold) throws Exception {
        SacnilkActorPageParser parser = new SacnilkActorPageParser();

        // Phase 1 — robots.txt
        log("Phase 1: Checking robots.txt crawl-delay...");
        long robotsMs     = parser.fetchCrawlDelayMs();
        long crawlDelayMs = Math.max(MIN_DELAY_MS, robotsMs);
        log(String.format("  robots.txt: %d ms  →  enforcing %d ms between requests.",
            robotsMs, crawlDelayMs));
        throttle(crawlDelayMs);

        // Phase 2 — load distinct actors from DB + ensure new columns exist
        log("Phase 2: Loading distinct actors from actors_data_collection...");
        List<String> dbActors;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            ensureNewColumnsExist(conn);
            dbActors = loadDistinctActors(conn);
        }
        log(String.format("  Found %d distinct actor(s) in the database.", dbActors.size()));

        if (dbActors.isEmpty()) {
            log("  No actors found — run --actor-scan (CSV import) first.");
            return;
        }

        // Phase 3 — supplement with any actor pages from sitemap (usually empty)
        log("Phase 3: Checking sacnilk sitemaps for bonus actor filmography pages...");
        Map<String, String> slugToUrl = parser.discoverActorFilmographyUrls();
        throttle(crawlDelayMs);
        log(String.format("  Found %d page(s) in sitemap (used as a supplement).", slugToUrl.size()));

        // Build normalised slug → url lookup for sitemap-discovered actors
        Map<String, String> normSlugToUrl = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : slugToUrl.entrySet()) {
            normSlugToUrl.put(normalize(e.getKey().replace("_", " ")), e.getValue());
        }

        // Phase 4 — build work list:
        //   Primary: construct URL directly from actor name (works for correctly-named pages)
        //   Supplement: prefer sitemap URL when a high-confidence fuzzy match exists
        log(String.format("Phase 4: Building work list for %d actor(s)...", dbActors.size()));
        List<String[]> workList = new ArrayList<>(); // [actorName, url]

        for (String actor : dbActors) {
            // Direct URL construction from DB actor name
            String directUrl  = parser.constructFilmographyUrl(actor);
            String resolvedUrl = directUrl;

            // Override with sitemap URL if we find a high-confidence fuzzy match
            if (!normSlugToUrl.isEmpty()) {
                String normActor = normalize(actor);
                String bestKey   = null;
                double bestScore = 0;
                for (String slugKey : normSlugToUrl.keySet()) {
                    double s = similarity(normActor, slugKey);
                    if (s > bestScore) { bestScore = s; bestKey = slugKey; }
                }
                if (bestScore >= threshold && bestKey != null) {
                    resolvedUrl = normSlugToUrl.get(bestKey);
                    log(String.format("  Sitemap match: '%-35s' → %s  (score=%.2f)", actor, resolvedUrl, bestScore));
                }
            }

            workList.add(new String[]{actor, resolvedUrl});
        }
        log(String.format("  Work list: %d actor(s) to attempt.", workList.size()));

        // Phase 5 — fetch each filmography page and upsert (with crawl-delay)
        log(String.format(
            "Phase 5: Crawling %d actor filmography page(s) with %d ms delay between requests...",
            workList.size(), crawlDelayMs));

        int found404  = 0;
        int found200  = 0;
        int totalFound    = 0;
        int totalUpserted = 0;
        int errors        = 0;
        long lastRequestAt = 0;

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            conn.setAutoCommit(false);

            for (int i = 0; i < workList.size(); i++) {
                String actorName = workList.get(i)[0];
                String actorUrl  = workList.get(i)[1];

                // Honour crawl-delay
                long elapsed = System.currentTimeMillis() - lastRequestAt;
                if (elapsed < crawlDelayMs) Thread.sleep(crawlDelayMs - elapsed);

                log(String.format("  [%d/%d] '%s'", i + 1, workList.size(), actorName));
                log(String.format("         → %s", actorUrl));

                try {
                    List<ActorMovieEntry> movies = parser.parseActorFilmography(actorName, actorUrl);
                    lastRequestAt = System.currentTimeMillis();

                    if (movies.isEmpty()) {
                        // Retry with last-two-char transposition to catch sacnilk URL typos
                        // (e.g. "Akshay Kumar" → primary "Akshay_Kumar" fails, typo "Akshay_Kumra" works)
                        String typoUrl = parser.constructTypoVariantUrl(actorName);
                        if (typoUrl != null) {
                            long elapsed2 = System.currentTimeMillis() - lastRequestAt;
                            if (elapsed2 < crawlDelayMs) Thread.sleep(crawlDelayMs - elapsed2);
                            movies = parser.parseActorFilmography(actorName, typoUrl);
                            lastRequestAt = System.currentTimeMillis();
                            if (!movies.isEmpty()) {
                                actorUrl = typoUrl;
                                log(String.format("         (typo variant matched: %s)", typoUrl));
                            }
                        }
                    }

                    if (movies.isEmpty()) {
                        found404++;
                        log("         No page found or no parseable movie data (page may not exist or slug differs).");
                        continue;
                    }

                    found200++;
                    totalFound += movies.size();
                    int upserted = upsertActorMovies(conn, movies, actorUrl);
                    totalUpserted += upserted;
                    log(String.format("         movies found (post-1980): %d  |  rows upserted/updated: %d",
                        movies.size(), upserted));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    logErr(String.format("         Error: %s", e.getMessage()));
                }
            }
        }

        log(String.format(
            "=== Phase 5 done — attempted: %d | pages found: %d | not found/no data: %d | " +
            "movies found: %d | rows upserted: %d | errors: %d ===",
            workList.size(), found200, found404, totalFound, totalUpserted, errors));
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    /**
     * Adds new columns required by the sacnilk actor crawler if they don't already exist.
     * Safe to call on every startup — uses information_schema checks.
     */
    private void ensureNewColumnsExist(Connection conn) throws SQLException {
        // Verify table exists before touching it
        String checkTable =
            "SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema='public' AND table_name=?";
        try (PreparedStatement ps = conn.prepareStatement(checkTable)) {
            ps.setString(1, TABLE_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return; // table not yet created by the CSV importer — skip
            }
        }

        // (column_name → DDL fragment)
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("status",      "\"status\"      TEXT DEFAULT NULL");
        columns.put("sacnilk_url", "\"sacnilk_url\" TEXT DEFAULT NULL");

        String checkCol =
            "SELECT 1 FROM information_schema.columns " +
            "WHERE table_schema='public' AND table_name=? AND column_name=?";

        conn.setAutoCommit(true);
        for (Map.Entry<String, String> col : columns.entrySet()) {
            boolean exists;
            try (PreparedStatement ps = conn.prepareStatement(checkCol)) {
                ps.setString(1, TABLE_NAME);
                ps.setString(2, col.getKey());
                try (ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
            }
            if (!exists) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE " + q(TABLE_NAME) + " ADD COLUMN " + col.getValue());
                }
                log("Added column '" + col.getKey() + "' to " + TABLE_NAME + ".");
            }
        }
        conn.setAutoCommit(false);
    }

    private List<String> loadDistinctActors(Connection conn) throws SQLException {
        List<String> actors = new ArrayList<>();
        String sql = "SELECT DISTINCT \"actor_name\" FROM " + q(TABLE_NAME) +
                     " WHERE \"actor_name\" IS NOT NULL ORDER BY \"actor_name\"";
        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {
            while (rs.next()) actors.add(rs.getString(1));
        }
        return actors;
    }

    /**
     * Upserts actor-movie entries using the existing PK (actor_name, movie_name, release_date).
     * Existing column values take precedence over sacnilk-derived values (CSV data is trusted more).
     * sacnilk_url is always written — it records the discovery source.
     *
     * Deduplication check: the ON CONFLICT covers (actor_name, movie_name, release_date).
     * When the user wants to differentiate by language, identical movie+date rows from different
     * languages are stored in a single row with the language from the highest-priority source.
     */
    private int upsertActorMovies(Connection conn, List<ActorMovieEntry> movies,
                                   String sacnilkUrl) throws SQLException {
        if (movies.isEmpty()) return 0;

        // Columns we write: actor_name, movie_name, release_date, language, genre,
        //                   director, character_name, sacnilk_url
        // On conflict: prefer existing values for most fields; always update sacnilk_url.
        String sql =
            "INSERT INTO " + q(TABLE_NAME) + " " +
            "(\"actor_name\", \"movie_name\", \"release_date\", " +
            " \"language\", \"genre\", \"director\", \"character_name\", \"sacnilk_url\") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (\"actor_name\", \"movie_name\", \"release_date\") DO UPDATE SET " +
            // Prefer whatever is already in the table; only fill NULLs from sacnilk
            "  \"language\"       = COALESCE(" + q(TABLE_NAME) + ".\"language\",       EXCLUDED.\"language\"), " +
            "  \"genre\"          = COALESCE(" + q(TABLE_NAME) + ".\"genre\",          EXCLUDED.\"genre\"), " +
            "  \"director\"       = COALESCE(" + q(TABLE_NAME) + ".\"director\",       EXCLUDED.\"director\"), " +
            "  \"character_name\" = COALESCE(" + q(TABLE_NAME) + ".\"character_name\", EXCLUDED.\"character_name\"), " +
            "  \"sacnilk_url\"    = EXCLUDED.\"sacnilk_url\""; // always record the source URL

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ActorMovieEntry e : movies) {
                if (e.movieName() == null || e.movieName().isBlank()) continue;
                if (e.releaseDate() == null) continue; // skip entries with no date

                ps.setString(1, e.actorName());
                ps.setString(2, e.movieName());
                ps.setString(3, e.releaseDate());
                ps.setString(4, e.language());
                ps.setString(5, e.genre());
                ps.setString(6, e.director());
                ps.setString(7, e.roleDescription());
                ps.setString(8, sacnilkUrl);
                ps.addBatch();
                count++;
            }
            if (count > 0) {
                ps.executeBatch();
                conn.commit();
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Name normalisation + Levenshtein similarity
    // -------------------------------------------------------------------------

    static String normalize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        int minLen = Math.min(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        // Reject if shorter string is less than 40% of longer — prevents spurious short matches
        if ((double) minLen / maxLen < 0.4) return 0.0;
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1)
                    ? prev[j - 1]
                    : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private void throttle(long ms) throws InterruptedException { Thread.sleep(ms); }

    private boolean sleep(long ms, String reason) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted during " + reason + " — stopping.");
            return false;
        }
    }

    private static String q(String id) { return "\"" + id.replace("\"", "\"\"") + "\""; }
    private void log(String msg)    { System.out.println(PREFIX + msg); }
    private void logErr(String msg) { System.err.println(PREFIX + msg); }

    private Properties loadProperties(String name, boolean required) {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                if (required) throw new RuntimeException(name + " not found on classpath");
                return props;
            }
            props.load(is);
        } catch (IOException e) {
            if (required) throw new RuntimeException("Cannot load " + name, e);
        }
        return props;
    }
}
