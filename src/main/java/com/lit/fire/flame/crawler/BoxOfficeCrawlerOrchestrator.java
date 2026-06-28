package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Orchestrates box-office enrichment from three sources in priority order:
 *
 *   1. sacnilk.com        – Indian box office data (INR Crore → USD)
 *   2. boxofficemojo.com  – Worldwide data in native USD (fills gaps after sacnilk)
 *   3. koimoi.com         – Indian box office data (INR Crore → USD; tertiary gap-fill)
 *
 * imdb.com is intentionally excluded: its robots.txt disallows automated crawlers
 * for all search paths (/search/, /find/) for agents not explicitly whitelisted.
 *
 * Each secondary source (BOM, Koimoi) only processes movies that still have
 * revenue=0 or budget=0 after the preceding source ran, so work is not duplicated.
 */
public class BoxOfficeCrawlerOrchestrator {

    private static final String PREFIX = "[CRAWLER] ";

    /**
     * Runs one complete multi-source enrichment cycle synchronously and returns.
     * Intended for the {@code --crawl} CLI mode.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies");

        long   bomDelay      = Long.parseLong(config.getProperty("crawler.bom.delay.ms",       "2000"));
        long   koimoiDelay   = Long.parseLong(config.getProperty("crawler.koimoi.delay.ms",    "2000"));
        double threshold     = Double.parseDouble(config.getProperty("crawler.match.threshold",  "0.70"));
        boolean bomEnabled   = Boolean.parseBoolean(config.getProperty("crawler.bom.enabled",   "true"));
        boolean koimoiEnabled= Boolean.parseBoolean(config.getProperty("crawler.koimoi.enabled","true"));

        // Phase 1: sacnilk (existing logic unchanged)
        log("=== Phase 1/3 — sacnilk.com ===");
        new SacnilkCrawlerService().runOnce();

        // Phase 2: Box Office Mojo
        if (bomEnabled) {
            log("=== Phase 2/3 — boxofficemojo.com (gap-fill) ===");
            if (isPathAllowed("https://www.boxofficemojo.com", "/search/") &&
                isPathAllowed("https://www.boxofficemojo.com", "/releasegroup/")) {
                runBomCycle(dbUrl, dbUser, dbPassword, tableName, bomDelay, threshold);
            } else {
                log("[BOM] Skipping — /search/ or /releasegroup/ disallowed by robots.txt.");
            }
        }

        // Phase 3: Koimoi
        if (koimoiEnabled) {
            log("=== Phase 3/3 — koimoi.com (gap-fill) ===");
            if (isPathAllowed("https://www.koimoi.com", "/")) {
                runKoimoiCycle(dbUrl, dbUser, dbPassword, tableName, koimoiDelay, threshold);
            } else {
                log("[Koimoi] Skipping — crawling disallowed by robots.txt.");
            }
        }

        log("=== Multi-source enrichment cycle complete ===");
    }

    // ---- Box Office Mojo cycle ----

    private void runBomCycle(String dbUrl, String dbUser, String dbPassword,
                              String tableName, long delayMs, double threshold) throws Exception {
        List<String[]> missing = loadMissing(dbUrl, dbUser, dbPassword, tableName, "[BOM]");
        if (missing == null || missing.isEmpty()) return;

        BoxOfficeMojoParser parser = new BoxOfficeMojoParser();
        int updated = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (String[] movie : missing) {
                String name        = movie[0];
                String year        = movie[1];

                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.searchAndParse(name, year, threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec == null || (rec.revenueUsd() == null && rec.budgetUsd() == null)) {
                        noData++;
                        continue;
                    }

                    int rows = db.updateBoxOfficeIfMissing(name, year, rec.revenueUsd(), rec.budgetUsd());
                    if (rows > 0) {
                        updated++;
                        log(String.format(
                            "[BOM] Updated '%-45s' (%s) | WW: %s | Budget: %s",
                            name, year,
                            rec.revenueUsd() != null ? "$" + String.format("%,d", rec.revenueUsd()) : "N/A",
                            rec.budgetUsd()  != null ? "$" + String.format("%,d", rec.budgetUsd())  : "N/A"));
                    } else {
                        noData++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    logErr(String.format("[BOM] Error for '%s' (%s): %s", name, year, e.getMessage()));
                }
            }
        }

        log(String.format("[BOM] Done — updated: %d | no data: %d | errors: %d",
            updated, noData, errors));
    }

    // ---- Koimoi cycle ----

    private void runKoimoiCycle(String dbUrl, String dbUser, String dbPassword,
                                 String tableName, long delayMs, double threshold) throws Exception {
        List<String[]> missing = loadMissing(dbUrl, dbUser, dbPassword, tableName, "[Koimoi]");
        if (missing == null || missing.isEmpty()) return;

        KoimoiParser       parser       = new KoimoiParser();
        ExchangeRateService exchangeRate = new ExchangeRateService();

        int updated = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (String[] movie : missing) {
                String name        = movie[0];
                String year        = movie[1];
                String releaseDate = movie[2];

                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.searchAndParse(name, year, threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec == null || (rec.worldwideCr() == null && rec.budgetCr() == null)) {
                        noData++;
                        continue;
                    }

                    double inrUsdRate = exchangeRate.getInrToUsdRate(releaseDate);
                    Long revenueUsd = rec.worldwideCr() != null
                        ? exchangeRate.inrCroreToUsd(rec.worldwideCr(), inrUsdRate) : null;
                    Long budgetUsd = rec.budgetCr() != null
                        ? exchangeRate.inrCroreToUsd(rec.budgetCr(), inrUsdRate) : null;

                    int rows = db.updateBoxOfficeIfMissing(name, year, revenueUsd, budgetUsd);
                    if (rows > 0) {
                        updated++;
                        log(String.format(
                            "[Koimoi] Updated '%-45s' (%s) | rate=%.5f | WW: %s | Budget: %s",
                            name, year, inrUsdRate,
                            revenueUsd != null ? "$" + String.format("%,d", revenueUsd) : "N/A",
                            budgetUsd  != null ? "$" + String.format("%,d", budgetUsd)  : "N/A"));
                    } else {
                        noData++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    logErr(String.format("[Koimoi] Error for '%s' (%s): %s", name, year, e.getMessage()));
                }
            }
        }

        // Persist exchange rates collected during this cycle
        Map<String, Double> rates = exchangeRate.getCachedRates();
        if (!rates.isEmpty()) {
            try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
                db.ensureRateTableExists();
                for (Map.Entry<String, Double> entry : rates.entrySet()) {
                    db.upsertExchangeRate(entry.getKey(), "INR", "USD", entry.getValue());
                    log(String.format("[Koimoi] Saved rate: %s  INR→USD = %.5f",
                        entry.getKey(), entry.getValue()));
                }
            }
        }

        log(String.format("[Koimoi] Done — updated: %d | no data: %d | errors: %d",
            updated, noData, errors));
    }

    // ---- helpers ----

    private List<String[]> loadMissing(String dbUrl, String dbUser, String dbPassword,
                                        String tableName, String tag) throws Exception {
        try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                log(tag + " Table not found — skipping.");
                return null;
            }
            List<String[]> missing = db.getMoviesMissingBoxOffice();
            log(String.format("%s %d movie(s) still missing box office data.", tag, missing.size()));
            return missing;
        }
    }

    private void throttle(long lastRequestAt, long delayMs) throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAt;
        if (lastRequestAt > 0 && elapsed < delayMs) Thread.sleep(delayMs - elapsed);
    }

    /**
     * Fetches robots.txt for the site and returns true when the given path is
     * not disallowed for the wildcard (*) user-agent.
     * Returns true (assume allowed) when robots.txt cannot be fetched.
     */
    private boolean isPathAllowed(String siteBase, String path) {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(siteBase + "/robots.txt"))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return true;
            return !isDisallowedByWildcard(resp.body(), path);
        } catch (Exception e) {
            log("Could not fetch robots.txt for " + siteBase + " (" + e.getMessage() + ") — proceeding.");
            return true;
        }
    }

    private boolean isDisallowedByWildcard(String robotsTxt, String path) {
        boolean inWildcard = false;
        for (String line : robotsTxt.split("\n")) {
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.toLowerCase().startsWith("user-agent:")) {
                inWildcard = line.substring("user-agent:".length()).trim().equals("*");
            } else if (inWildcard && line.toLowerCase().startsWith("disallow:")) {
                String disallowed = line.substring("disallow:".length()).trim();
                if (!disallowed.isEmpty() && path.startsWith(disallowed)) return true;
            }
        }
        return false;
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
