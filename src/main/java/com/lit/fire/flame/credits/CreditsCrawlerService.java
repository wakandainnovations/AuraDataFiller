package com.lit.fire.flame.credits;

import com.lit.fire.flame.crawler.SacnilkHtmlParser;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;

/**
 * Background service that fills the "directors" and "production_companies" columns in
 * movies_data_collection.
 *
 * Source: sacnilk.com — the only site already crawled by this app whose movie detail
 * pages carry structured director/production credits (a "🎬 Director:" / "🏢 Production:"
 * block in the Movie Information sidebar, present whenever sacnilk has the data).
 * boxofficemojo.com's title pages don't expose crew credits without an IMDb Pro
 * subscription, so sacnilk is the only source here.
 *
 * Scope: Indian-language movies released after 2000, matched to sacnilk sitemap slugs by
 * year + fuzzy title match.
 *
 * Thread model mirrors RuntimeBudgetCrawlerService: run() loops forever with a configurable
 * interval (default 24h); runOnce() does a single cycle for one-shot/manual invocation
 * (--credits-scan-once).
 */
public class CreditsCrawlerService implements Runnable {

    private static final String PREFIX = "[CREDITS] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("credits.enabled", "true"))) {
            log("Disabled via credits.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("credits.initial.delay.ms", "25000"));
        long intervalMs     = Long.parseLong(config.getProperty("credits.interval.hours",   "24")) * 3_600_000L;

        if (!sleep(initialDelayMs, "initial startup delay")) return;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCycle(secrets, config);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logErr("Cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }

            log(String.format("Next cycle in %d hour(s). Sleeping...", intervalMs / 3_600_000L));
            if (!sleep(intervalMs, "inter-cycle interval")) break;
        }
        log("Service stopped.");
    }

    /**
     * Runs exactly one enrichment cycle synchronously, then returns.
     * Intended for the {@code --credits-scan-once} CLI mode.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    // ---- cycle ----

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting directors/production_companies enrichment cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        int    batchSize   = Integer.parseInt(config.getProperty("credits.candidate.batch.size", "3000"));
        double threshold   = Double.parseDouble(config.getProperty("credits.match.threshold",    "0.70"));
        long   delayMs     = Long.parseLong(config.getProperty("credits.sacnilk.delay.ms", "1500"));
        int    recheckDays = Integer.parseInt(config.getProperty("credits.recheck.interval.days", "14"));

        List<CreditsDatabaseService.Candidate> candidates;
        try (CreditsDatabaseService db = new CreditsDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                log("Table '" + tableName + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnsExist();
            candidates = db.getMoviesMissingCredits(batchSize, recheckDays);
        }
        log(String.format(
            "Found %,d Indian-language movie(s) missing director/production data (released after 2000, most recent year first).",
            candidates.size()));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible movie already has both fields, or is within its recheck cooldown.");
            return;
        }

        SacnilkHtmlParser parser = new SacnilkHtmlParser();

        log("Fetching movie list from sacnilk sitemap...");
        List<String> slugs = parser.fetchMovieSlugsFromSitemap();
        log(String.format("Found %,d movie slug(s).", slugs.size()));
        Thread.sleep(delayMs);

        // Group candidates by year for matching, same approach as RuntimeBudgetCrawlerService.
        Map<String, List<CreditsDatabaseService.Candidate>> byYear = new HashMap<>();
        for (CreditsDatabaseService.Candidate c : candidates) {
            byYear.computeIfAbsent(c.year(), k -> new ArrayList<>()).add(c);
        }

        record Match(String slug, CreditsDatabaseService.Candidate candidate) {}
        List<Match> matched = new ArrayList<>();
        for (String slug : slugs) {
            String year = parser.extractYearFromSlug(slug);
            if (year == null) continue;

            List<CreditsDatabaseService.Candidate> candidatesForYear = byYear.get(year);
            if (candidatesForYear == null || candidatesForYear.isEmpty()) continue;

            String slugNorm = normalize(parser.extractNameFromSlug(slug));
            CreditsDatabaseService.Candidate best = null;
            double bestScore = 0;
            for (CreditsDatabaseService.Candidate cand : candidatesForYear) {
                double score = similarity(slugNorm, normalize(cand.movieName()));
                if (score > bestScore) {
                    bestScore = score;
                    best      = cand;
                }
            }
            if (bestScore >= threshold) {
                matched.add(new Match(slug, best));
            }
        }
        log(String.format("Matched %,d movie(s) needing director/production data to sacnilk slugs.", matched.size()));

        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;
        Set<CreditsDatabaseService.Candidate> filledSet  = new HashSet<>();
        Set<CreditsDatabaseService.Candidate> erroredSet = new HashSet<>();

        try (CreditsDatabaseService db = new CreditsDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (Match m : matched) {
                String slug = m.slug();
                CreditsDatabaseService.Candidate c = m.candidate();

                throttle(lastRequestAt, delayMs);

                try {
                    SacnilkHtmlParser.CreditsInfo credits = parser.fetchCredits(slug);
                    lastRequestAt = System.currentTimeMillis();

                    if (credits == null) {
                        noData++;
                        continue;
                    }

                    int rows = db.updateCreditsIfMissing(c.movieName(), c.year(),
                        credits.directors(), credits.productionCompanies());
                    if (rows > 0) {
                        filled++;
                        // Only skip the recheck cooldown when sacnilk had BOTH fields — a
                        // partial result (e.g. director found, no production company listed)
                        // means the site simply doesn't have the rest, so re-fetching before
                        // credits.recheck.interval.days elapses would just repeat the same
                        // partial result forever. Falls through to markChecked() below instead.
                        if (credits.directors() != null && credits.productionCompanies() != null) {
                            filledSet.add(c);
                        }
                        log(String.format("Updated '%-45s' (%s) | director(s): %s | production: %s",
                            c.movieName(), c.year(),
                            credits.directors() != null ? credits.directors() : "n/a",
                            credits.productionCompanies() != null ? credits.productionCompanies() : "n/a"));
                    } else {
                        noData++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    erroredSet.add(c);
                    db.rollback();
                    logErr(String.format("Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }

            // Candidates neither filled nor errored — includes both sacnilk-matched-but-empty
            // movies and movies whose sitemap had no matching slug at all (the latter never
            // even entered the `matched` loop above). Mark checked-today so the next cycle's
            // batch moves on to fresh candidates instead of retrying these forever; picked up
            // again automatically after credits.recheck.interval.days.
            String today = LocalDate.now().toString();
            for (CreditsDatabaseService.Candidate c : candidates) {
                if (!filledSet.contains(c) && !erroredSet.contains(c)) {
                    db.markChecked(c.movieName(), c.year(), today);
                }
            }
        }

        log(String.format(
            "=== Cycle complete — %,d candidate(s) considered | matched to sacnilk: %,d | filled: %,d | " +
            "no data: %,d | errors: %,d (rechecked in %d day(s)) ===",
            candidates.size(), matched.size(), filled, noData, errors, recheckDays));
    }

    // ---- name normalisation and similarity (mirrors RuntimeBudgetCrawlerService/SacnilkCrawlerService) ----

    static String normalize(String name) {
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9]+", " ")
                   .trim();
    }

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

    private void throttle(long lastRequestAt, long delayMs) throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAt;
        if (lastRequestAt > 0 && elapsed < delayMs) Thread.sleep(delayMs - elapsed);
    }

    // Explicit flush() matters here: this service is meant to run for days/weeks as a
    // detached background process with stdout redirected to a log file, and a redirected-
    // to-file stdout is block-buffered rather than line-buffered.
    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
    }

    private void logErr(String msg) {
        System.err.println(PREFIX + msg);
        System.err.flush();
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
