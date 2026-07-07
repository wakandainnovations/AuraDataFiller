package com.lit.fire.flame.actor;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * Background service that crawls supplementary sources — in.kulfiy.com and
 * fandango.com — for actor filmographies that sacnilk may have missed (mainly
 * south Indian movies). Upserts newly-discovered movies into actors_data_collection.
 *
 * Unlike the sacnilk crawler (which upserts and fills NULLs on every existing row),
 * this service only ADDS movies that are entirely missing for the actor: if a
 * matching (actor, movie, year) row already exists, it is left untouched and the
 * entry is skipped.
 *
 * Neither kulfiy nor fandango expose a language field, so language is resolved via
 * an LLM lookup (Claude Haiku) when the row doesn't already carry one; "Unknown" is
 * stored if the LLM can't determine it either.
 *
 * Lifecycle: started as a daemon thread by ActorDataCollectionService, alongside
 * the sacnilk crawler. Runs one full crawl cycle then sleeps for the configured
 * interval (default 24 h).
 *
 * New columns added automatically if absent: kulfiy_url, fandango_url.
 */
public class SupplementalActorCrawlerService implements Runnable {

    private static final String PREFIX     = "[ACTOR-CRAWLER-SUPP] ";
    private static final String TABLE_NAME = "actors_data_collection";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("actor.crawler.supplemental.enabled", "true"))) {
            log("Disabled via actor.crawler.supplemental.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("actor.crawler.supplemental.initial.delay.ms", "45000"));
        long intervalMs     = Long.parseLong(config.getProperty("actor.crawler.supplemental.interval.hours", "24")) * 3_600_000L;

        sleep(initialDelayMs, "initial startup delay");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                log("=== Starting supplemental (kulfiy/fandango) actor crawl cycle ===");
                runCrawlCycle(secrets, config);
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

    /** Runs exactly one crawl cycle synchronously, then returns. For CLI one-shot use. */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        log("=== Starting supplemental (kulfiy/fandango) actor crawl cycle (one-shot) ===");
        runCrawlCycle(secrets, config);
    }

    // -------------------------------------------------------------------------
    // Crawl cycle
    // -------------------------------------------------------------------------

    private void runCrawlCycle(Properties secrets, Properties config) throws Exception {
        String dbUrl  = secrets.getProperty("db.url");
        String dbUser = secrets.getProperty("db.user");
        String dbPass = secrets.getProperty("db.password", "");

        boolean kulfiyEnabled   = Boolean.parseBoolean(config.getProperty("actor.crawler.kulfiy.enabled", "true"));
        boolean fandangoEnabled = Boolean.parseBoolean(config.getProperty("actor.crawler.fandango.enabled", "true"));
        long kulfiyMinDelayMs   = Long.parseLong(config.getProperty("actor.crawler.kulfiy.delay.ms", "2000"));
        long fandangoMinDelayMs = Long.parseLong(config.getProperty("actor.crawler.fandango.delay.ms", "2000"));
        double matchThreshold   = Double.parseDouble(config.getProperty("actor.crawler.match.threshold", "0.75"));

        String anthropicKey = secrets.getProperty("anthropic.api.key");
        boolean llmEnabled = Boolean.parseBoolean(config.getProperty("actor.crawler.language.llm.enabled", "true"))
            && anthropicKey != null && !anthropicKey.isBlank();
        long llmDelayMs = Long.parseLong(config.getProperty("actor.crawler.language.llm.delay.ms", "300"));
        ClaudeLanguageClient languageClient = llmEnabled ? new ClaudeLanguageClient(anthropicKey, llmDelayMs) : null;
        if (!llmEnabled) log("LLM language fallback disabled (no API key or config off) — unresolved languages will be stored as 'Unknown'.");

        KulfiyActorPageParser   kulfiyParser   = new KulfiyActorPageParser();
        FandangoActorPageParser fandangoParser = new FandangoActorPageParser();

        // Phase 0 — robots.txt: honour each site's declared Crawl-delay (floored at the
        // configured minimum) and Disallow rules for the paths this crawler actually uses.
        log("Phase 0: Checking robots.txt for kulfiy.com and fandango.com...");
        RobotsTxtPolicy kulfiyRobots   = kulfiyParser.fetchRobotsPolicy();
        RobotsTxtPolicy fandangoRobots = fandangoParser.fetchRobotsPolicy();
        long kulfiyDelayMs   = Math.max(kulfiyMinDelayMs, kulfiyRobots.crawlDelayMs());
        long fandangoDelayMs = Math.max(fandangoMinDelayMs, fandangoRobots.crawlDelayMs());
        log(String.format("  kulfiy.com:   robots crawl-delay=%d ms → enforcing %d ms.", kulfiyRobots.crawlDelayMs(), kulfiyDelayMs));
        log(String.format("  fandango.com: robots crawl-delay=%d ms → enforcing %d ms.", fandangoRobots.crawlDelayMs(), fandangoDelayMs));

        if (kulfiyEnabled && !kulfiyRobots.isAllowed("/movies/")) {
            kulfiyEnabled = false;
            log("  kulfiy.com disallows /movies/ for User-agent: * in robots.txt — disabling kulfiy for this cycle.");
        }
        if (fandangoEnabled && (!fandangoRobots.isAllowed("/search") || !fandangoRobots.isAllowed("/people/"))) {
            fandangoEnabled = false;
            log("  fandango.com disallows /search or /people/ for User-agent: * in robots.txt — disabling fandango for this cycle.");
        }

        List<String> dbActors;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            ensureNewColumnsExist(conn);
            dbActors = loadDistinctActors(conn);
        }
        log(String.format("Found %d distinct actor(s) in the database.", dbActors.size()));
        if (dbActors.isEmpty()) {
            log("No actors found — run --actor-scan (CSV import) first.");
            return;
        }

        // Optional cap for staged/trial runs — a full cycle over every actor can take many
        // hours at a polite crawl-delay. 0 (default) means no limit.
        int maxActors = Integer.parseInt(config.getProperty("actor.crawler.supplemental.max.actors", "0"));
        if (maxActors > 0 && dbActors.size() > maxActors) {
            List<String> sample = new ArrayList<>(dbActors);
            Collections.shuffle(sample);
            dbActors = sample.subList(0, maxActors);
            log(String.format("actor.crawler.supplemental.max.actors=%d — limiting this cycle to a random sample of %d actor(s).",
                maxActors, dbActors.size()));
        }

        int kulfiyPagesFound = 0, kulfiyMoviesFound = 0, kulfiyInserted = 0;
        int fandangoPagesFound = 0, fandangoMoviesFound = 0, fandangoInserted = 0;
        int errors = 0;

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            conn.setAutoCommit(false);

            for (int i = 0; i < dbActors.size(); i++) {
                String actorName = dbActors.get(i);
                log(String.format("[%d/%d] '%s'", i + 1, dbActors.size(), actorName));

                if (kulfiyEnabled) {
                    try {
                        Thread.sleep(kulfiyDelayMs);
                        String url = kulfiyParser.constructMoviesUrl(actorName);
                        List<ActorMovieEntry> movies = kulfiyParser.parseActorMovies(actorName, url);
                        if (!movies.isEmpty()) {
                            kulfiyPagesFound++;
                            kulfiyMoviesFound += movies.size();
                            int ins = insertMissingMovies(conn, actorName, movies, url, "kulfiy_url", languageClient);
                            kulfiyInserted += ins;
                            log(String.format("         kulfiy: found %d movie(s), inserted %d new", movies.size(), ins));
                        }
                    } catch (Exception e) {
                        errors++;
                        try { conn.rollback(); } catch (SQLException ignored) {}
                        logErr("         kulfiy error: " + e.getMessage());
                    }
                }

                if (fandangoEnabled) {
                    try {
                        Thread.sleep(fandangoDelayMs);
                        String creditsUrl = fandangoParser.findFilmCreditsUrl(actorName, matchThreshold);
                        if (creditsUrl != null) {
                            Thread.sleep(fandangoDelayMs);
                            List<ActorMovieEntry> movies = fandangoParser.parseFilmCredits(actorName, creditsUrl);
                            if (!movies.isEmpty()) {
                                fandangoPagesFound++;
                                fandangoMoviesFound += movies.size();
                                int ins = insertMissingMovies(conn, actorName, movies, creditsUrl, "fandango_url", languageClient);
                                fandangoInserted += ins;
                                log(String.format("         fandango: found %d movie(s), inserted %d new", movies.size(), ins));
                            }
                        }
                    } catch (Exception e) {
                        errors++;
                        try { conn.rollback(); } catch (SQLException ignored) {}
                        logErr("         fandango error: " + e.getMessage());
                    }
                }
            }
        }

        log(String.format(
            "=== Done — kulfiy: %d page(s), %d movie(s) found, %d inserted | " +
            "fandango: %d page(s), %d movie(s) found, %d inserted | errors: %d ===",
            kulfiyPagesFound, kulfiyMoviesFound, kulfiyInserted,
            fandangoPagesFound, fandangoMoviesFound, fandangoInserted, errors));
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    private void ensureNewColumnsExist(Connection conn) throws SQLException {
        String checkTable =
            "SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema='public' AND table_name=?";
        try (PreparedStatement ps = conn.prepareStatement(checkTable)) {
            ps.setString(1, TABLE_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return; // table not yet created by the CSV importer — skip
            }
        }

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("kulfiy_url",   "\"kulfiy_url\"   TEXT DEFAULT NULL");
        columns.put("fandango_url", "\"fandango_url\" TEXT DEFAULT NULL");

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
     * For each parsed movie, skips it if a matching (actor, movie, year) row already
     * exists in the DB; otherwise resolves language (LLM fallback) and inserts a new
     * row recording the discovery source URL. Returns the number of rows inserted.
     */
    private int insertMissingMovies(Connection conn, String actorName, List<ActorMovieEntry> movies,
                                     String sourceUrl, String sourceUrlColumn,
                                     ClaudeLanguageClient languageClient) throws SQLException {
        String existsSql =
            "SELECT 1 FROM " + q(TABLE_NAME) + " " +
            "WHERE lower(trim(\"actor_name\")) = lower(trim(?)) " +
            "  AND lower(trim(\"movie_name\")) = lower(trim(?)) " +
            "  AND left(\"release_date\", 4) = left(?, 4)";

        String insertSql =
            "INSERT INTO " + q(TABLE_NAME) + " " +
            "(\"actor_name\", \"movie_name\", \"release_date\", \"language\", \"genre\", " +
            " \"character_name\", " + q(sourceUrlColumn) + ") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (\"actor_name\", \"movie_name\", \"release_date\") DO NOTHING";

        int inserted = 0;
        try (PreparedStatement existsPs = conn.prepareStatement(existsSql);
             PreparedStatement insertPs = conn.prepareStatement(insertSql)) {

            for (ActorMovieEntry e : movies) {
                if (e.movieName() == null || e.movieName().isBlank()) continue;
                if (e.releaseDate() == null) continue;

                existsPs.setString(1, actorName);
                existsPs.setString(2, e.movieName());
                existsPs.setString(3, e.releaseDate());
                boolean exists;
                try (ResultSet rs = existsPs.executeQuery()) { exists = rs.next(); }
                if (exists) continue; // already in the DB — skip, don't touch it

                String language = e.language();
                if ((language == null || language.isBlank()) && languageClient != null) {
                    language = languageClient.fetchLanguage(e.movieName(), e.releaseDate());
                } else if (language == null || language.isBlank()) {
                    language = "Unknown";
                }

                insertPs.setString(1, actorName);
                insertPs.setString(2, e.movieName());
                insertPs.setString(3, e.releaseDate());
                insertPs.setString(4, language);
                insertPs.setString(5, e.genre());
                insertPs.setString(6, e.roleDescription());
                insertPs.setString(7, sourceUrl);
                inserted += insertPs.executeUpdate();
            }
            conn.commit();
        }
        return inserted;
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static String q(String id) { return "\"" + id.replace("\"", "\"\"") + "\""; }
    private void log(String msg)    { System.out.println(PREFIX + msg); }
    private void logErr(String msg) { System.err.println(PREFIX + msg); }

    private boolean sleep(long ms, String reason) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted during " + reason + " — stopping.");
            return false;
        }
    }

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
