package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Background service that periodically crawls sacnilk.com and updates the
 * revenue (Total Worldwide Collection) and budget columns in the movies table.
 *
 * Crawl policy (as per sacnilk.com/robots.txt):
 *   - All movie pages are explicitly allowed.
 *   - robots.txt specifies Crawl-delay: 1 second.
 *   - This service defaults to 1 500 ms between HTTP requests to stay polite.
 *
 * Thread model:
 *   - Runs as a daemon thread started by App at every application startup.
 *   - An initial delay (default 10 s) lets CSV processing begin first.
 *   - After each crawl cycle, sleeps for a configurable interval (default 24 h).
 *
 * Matching strategy:
 *   1. Fetch sitemap-movies.xml once → ~1 000 movie slugs.
 *   2. Load all (movie_name, year) pairs from the database into memory.
 *   3. For each sacnilk slug, normalise the name and year, then find the
 *      best-matching DB entry using Levenshtein-ratio similarity.
 *   4. For each match above the configured threshold (default 0.70), fetch
 *      the movie's detail page and parse WW collection + budget.
 *   5. Write the data back to the database.
 *   6. Sleep until the next cycle.
 */
public class SacnilkCrawlerService implements Runnable {

    private static final String PREFIX = "[CRAWLER] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("crawler.enabled", "true"))) {
            log("Disabled via crawler.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("crawler.initial.delay.ms",  "10000"));
        long crawlDelayMs   = Long.parseLong(config.getProperty("crawler.request.delay.ms",  "1500"));
        long intervalMs     = Long.parseLong(config.getProperty("crawler.interval.hours",    "24")) * 3_600_000L;
        double threshold    = Double.parseDouble(config.getProperty("crawler.match.threshold", "0.70"));
        String tableName    = config.getProperty("table.name", "movies");

        sleep(initialDelayMs, "initial startup delay");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCrawlCycle(secrets, tableName, crawlDelayMs, threshold);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logErr("Crawl cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }

            log(String.format("Next crawl in %d hour(s). Sleeping...", intervalMs / 3_600_000L));
            if (!sleep(intervalMs, "inter-cycle interval")) break;
        }
        log("Service stopped.");
    }

    // ---- crawl cycle ----

    private void runCrawlCycle(Properties secrets, String tableName,
                                long crawlDelayMs, double threshold) throws Exception {
        log("=== Starting box-office enrichment cycle from sacnilk.com ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");

        SacnilkHtmlParser parser = new SacnilkHtmlParser();

        // --- Phase 1: discover movie slugs via sitemap ---
        log("Phase 1: Fetching movie list from sitemap...");
        List<String> slugs = parser.fetchMovieSlugsFromSitemap();
        log(String.format("Found %d movie slugs.", slugs.size()));
        throttle(crawlDelayMs);

        // --- Phase 2: load DB movies into memory ---
        log("Phase 2: Loading movies from database...");
        List<String[]> dbMovies; // each: [movie_name, 4-digit-year, release_date]
        try (CrawlerDatabaseService db =
                 new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {

            if (!db.tableExists()) {
                log("Table '" + tableName + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnsExist();
            dbMovies = db.getAllMovieNameYears();
        }
        log(String.format("Loaded %,d distinct movie entries from database.", dbMovies.size()));

        if (dbMovies.isEmpty()) {
            log("Database is empty — skipping cycle.");
            return;
        }

        // Build lookup: year → list of [original_name, normalized_name, release_date]
        Map<String, List<String[]>> dbByYear = new HashMap<>();
        for (String[] m : dbMovies) {
            dbByYear.computeIfAbsent(m[1], k -> new ArrayList<>())
                    .add(new String[]{m[0], normalize(m[0]), m[2]});
        }

        // --- Phase 3: match sacnilk slugs to DB entries ---
        log("Phase 3: Matching sacnilk movies to database entries (threshold=" + threshold + ")...");
        List<String[]> matched = new ArrayList<>(); // [slug, dbMovieName, year, releaseDate]

        for (String slug : slugs) {
            String year = parser.extractYearFromSlug(slug);
            if (year == null) continue;

            String sacnilkNorm = normalize(parser.extractNameFromSlug(slug));
            List<String[]> candidates = dbByYear.getOrDefault(year, List.of());

            String bestName        = null;
            String bestReleaseDate = null;
            double bestScore       = 0;
            for (String[] candidate : candidates) {
                double score = similarity(sacnilkNorm, candidate[1]);
                if (score > bestScore) {
                    bestScore       = score;
                    bestName        = candidate[0];
                    bestReleaseDate = candidate[2];
                }
            }

            if (bestScore >= threshold) {
                matched.add(new String[]{slug, bestName, year, bestReleaseDate});
            }
        }
        log(String.format("Matched %d sacnilk movie(s) to database entries.", matched.size()));

        if (matched.isEmpty()) {
            log("No matches found — nothing to update.");
            return;
        }

        // --- Phase 4: fetch detail pages, convert to USD, and update DB ---
        log(String.format("Phase 4: Fetching detail pages (delay: %d ms between requests)...", crawlDelayMs));

        int updated = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;
        ExchangeRateService exchangeRate = new ExchangeRateService();

        try (CrawlerDatabaseService db =
                 new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {

            for (String[] match : matched) {
                String slug        = match[0];
                String dbName      = match[1];
                String year        = match[2];
                String releaseDate = match[3];

                // Honour crawl-delay before every detail-page request
                long elapsed = System.currentTimeMillis() - lastRequestAt;
                if (elapsed < crawlDelayMs) {
                    Thread.sleep(crawlDelayMs - elapsed);
                }

                try {
                    BoxOfficeRecord rec = parser.parseMovieDetailPage(slug);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec.worldwideCr() == null && rec.budgetCr() == null) {
                        noData++;
                        continue;
                    }

                    double inrUsdRate = exchangeRate.getInrToUsdRate(releaseDate);
                    Long revenueUsd = rec.worldwideCr() != null
                        ? exchangeRate.inrCroreToUsd(rec.worldwideCr(), inrUsdRate) : null;
                    Long budgetUsd = rec.budgetCr() != null
                        ? exchangeRate.inrCroreToUsd(rec.budgetCr(), inrUsdRate) : null;

                    int rows = db.updateBoxOffice(dbName, year, revenueUsd, budgetUsd);
                    if (rows > 0) {
                        updated++;
                        log(String.format(
                            "Updated '%-45s' (%s) | rate=%.5f | WW: $%s | Budget: $%s",
                            dbName, year, inrUsdRate,
                            revenueUsd != null ? String.format("%,d", revenueUsd) : "N/A",
                            budgetUsd  != null ? String.format("%,d", budgetUsd)  : "N/A"));
                    } else {
                        noData++;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    logErr(String.format("Error processing slug '%s': %s", slug, e.getMessage()));
                }
            }
        }

        log(String.format(
            "=== Cycle complete — updated: %d | no data / no match: %d | errors: %d ===",
            updated, noData, errors));

        // --- Phase 5: persist fetched exchange rates to currency_rate_xe ---
        Map<String, Double> cachedRates = exchangeRate.getCachedRates();
        if (!cachedRates.isEmpty()) {
            log("Phase 5: Saving " + cachedRates.size() + " exchange rate(s) to currency_rate_xe...");
            try (CrawlerDatabaseService db =
                     new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
                db.ensureRateTableExists();
                for (Map.Entry<String, Double> entry : cachedRates.entrySet()) {
                    db.upsertExchangeRate(entry.getKey(), "INR", "USD", entry.getValue());
                    log(String.format("  Saved: %s  INR→USD = %.5f", entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    // ---- name normalisation and similarity ----

    /**
     * Normalises a movie name for matching: lowercase, collapse punctuation/whitespace to spaces.
     * "K.G.F: Chapter 2" → "kgf chapter 2"
     */
    static String normalize(String name) {
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9]+", " ")
                   .trim();
    }

    /**
     * Levenshtein-ratio similarity in [0, 1].
     * Returns 0 when the shorter string is less than half the length of the longer one,
     * preventing short film titles from spuriously matching long ones.
     */
    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        int minLen = Math.min(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        if ((double) minLen / maxLen < 0.5) return 0.0;
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

    // ---- helpers ----

    /** Sleeps for the given duration; returns false if interrupted. */
    private boolean sleep(long ms, String reason) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted during " + reason + " — stopping.");
            return false;
        }
    }

    /** Sleeps for the crawl delay and propagates InterruptedException to the caller. */
    private void throttle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
    }

    private void logErr(String msg) {
        System.err.println(PREFIX + msg);
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
}
