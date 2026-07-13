package com.lit.fire.flame.synopsis;

import com.lit.fire.flame.crawler.BoxOfficeMojoParser;
import com.lit.fire.flame.crawler.SacnilkHtmlParser;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;

/**
 * Background service that fills the "synopsis" column in movies_data_collection.
 *
 * Sources, in priority order:
 *   1. boxofficemojo.com – broad title coverage, clean full-sentence synopsis text
 *      pulled straight from the title/release-group page.
 *   2. sacnilk.com        – fallback for movies BOM has no page for (mostly India-only
 *      titles). Its pages have no dedicated synopsis section, so the text is recovered
 *      from the SEO meta description and is frequently truncated mid-sentence — used
 *      only when BOM has nothing.
 *
 * Scope: Indian-language movies released after 2000, already released (release_date <=
 * today when a full date is known), most recently released first (e.g. 2026 down to 2001).
 *
 * Thread model mirrors SacnilkCrawlerService/YoutubeEnrichmentService: run() loops
 * forever with a configurable interval (default 24h); runOnce() does a single cycle
 * for one-shot/manual invocation (--synopsis-scan-once).
 */
public class SynopsisCrawlerService implements Runnable {

    private static final String PREFIX = "[SYNOPSIS] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("synopsis.enabled", "true"))) {
            log("Disabled via synopsis.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("synopsis.initial.delay.ms", "20000"));
        long intervalMs     = Long.parseLong(config.getProperty("synopsis.interval.hours",   "24")) * 3_600_000L;

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
     * Intended for the {@code --synopsis-scan-once} CLI mode.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    // ---- cycle ----

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting synopsis enrichment cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        int    batchSize   = Integer.parseInt(config.getProperty("synopsis.candidate.batch.size", "3000"));
        double threshold   = Double.parseDouble(config.getProperty("synopsis.match.threshold",    "0.70"));
        long   bomDelayMs  = Long.parseLong(config.getProperty("synopsis.bom.delay.ms",     "2000"));
        long   sacnilkDelayMs = Long.parseLong(config.getProperty("synopsis.sacnilk.delay.ms", "1500"));
        int    recheckDays = Integer.parseInt(config.getProperty("synopsis.recheck.interval.days", "14"));

        List<SynopsisDatabaseService.Candidate> candidates;
        try (SynopsisDatabaseService db = new SynopsisDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                log("Table '" + tableName + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnExists();
            candidates = db.getMoviesMissingSynopsis(batchSize, recheckDays);
        }
        log(String.format(
            "Found %,d Indian-language movie(s) missing a synopsis (released after 2000, up to today, most recent year first).",
            candidates.size()));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible movie already has a synopsis, or is within its recheck cooldown.");
            return;
        }

        // --- Phase 1: boxofficemojo.com ---
        log(String.format("=== Phase 1/2 — boxofficemojo.com (%,d candidate movie(s)) ===", candidates.size()));
        int bomFilled = runBomPhase(dbUrl, dbUser, dbPassword, tableName, candidates, bomDelayMs, threshold);

        // --- Phase 2: sacnilk.com fallback for whatever BOM couldn't fill ---
        List<SynopsisDatabaseService.Candidate> stillMissing;
        try (SynopsisDatabaseService db = new SynopsisDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            stillMissing = db.getMoviesMissingSynopsis(batchSize, recheckDays);
        }
        log(String.format("=== Phase 2/2 — sacnilk.com fallback (%,d movie(s) still missing) ===", stillMissing.size()));
        PhaseResult sacnilkResult = runSacnilkPhase(dbUrl, dbUser, dbPassword, tableName, stillMissing, sacnilkDelayMs, threshold);

        // Movies neither source had anything for: mark checked-today so the next cycle's
        // batch moves on to fresh candidates instead of getting stuck re-trying these forever.
        // Retried again automatically after synopsis.recheck.interval.days, in case a movie
        // gets added to one of the sites in the meantime.
        if (!sacnilkResult.noData().isEmpty()) {
            String today = LocalDate.now().toString();
            try (SynopsisDatabaseService db = new SynopsisDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
                for (SynopsisDatabaseService.Candidate c : sacnilkResult.noData()) {
                    db.markChecked(c.movieName(), c.year(), today);
                }
            }
        }

        log(String.format(
            "=== Cycle complete — %,d candidate(s) considered | boxofficemojo.com filled: %,d | " +
            "sacnilk.com filled: %,d | not found by either source (rechecked in %d day(s)): %,d ===",
            candidates.size(), bomFilled, sacnilkResult.filled(), recheckDays, sacnilkResult.noData().size()));
    }

    /** Outcome of a fallback phase: how many rows it filled, and which candidates it found nothing for. */
    private record PhaseResult(int filled, List<SynopsisDatabaseService.Candidate> noData) {}

    // ---- boxofficemojo.com phase ----

    private int runBomPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                             List<SynopsisDatabaseService.Candidate> candidates,
                             long delayMs, double threshold) throws Exception {
        BoxOfficeMojoParser parser = new BoxOfficeMojoParser();
        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (SynopsisDatabaseService db = new SynopsisDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (SynopsisDatabaseService.Candidate c : candidates) {
                throttle(lastRequestAt, delayMs);

                try {
                    String synopsis = parser.searchAndParseSynopsis(c.movieName(), c.year(), threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (synopsis == null || synopsis.isBlank()) {
                        noData++;
                        continue;
                    }

                    int rows = db.updateSynopsisIfMissing(c.movieName(), c.year(), synopsis);
                    if (rows > 0) {
                        filled++;
                        log(String.format("[BOM] Updated '%-45s' (%s) | %s",
                            c.movieName(), c.year(), truncateForLog(synopsis)));
                    } else {
                        noData++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    db.rollback();
                    logErr(String.format("[BOM] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        log(String.format("[BOM] Done — filled: %,d | no synopsis found: %,d | errors: %,d (of %,d candidate(s))",
            filled, noData, errors, candidates.size()));
        return filled;
    }

    // ---- sacnilk.com phase ----

    private PhaseResult runSacnilkPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                         List<SynopsisDatabaseService.Candidate> candidates,
                                         long delayMs, double threshold) throws Exception {
        if (candidates.isEmpty()) {
            log("[Sacnilk] Nothing left to fill — skipping.");
            return new PhaseResult(0, List.of());
        }

        SacnilkHtmlParser parser = new SacnilkHtmlParser();

        log("[Sacnilk] Fetching movie list from sitemap...");
        List<String> slugs = parser.fetchMovieSlugsFromSitemap();
        log(String.format("[Sacnilk] Found %,d movie slug(s).", slugs.size()));
        Thread.sleep(delayMs);

        // Group remaining candidates by year for matching, same approach as SacnilkCrawlerService.
        Map<String, List<SynopsisDatabaseService.Candidate>> byYear = new HashMap<>();
        for (SynopsisDatabaseService.Candidate c : candidates) {
            byYear.computeIfAbsent(c.year(), k -> new ArrayList<>()).add(c);
        }

        record Match(String slug, SynopsisDatabaseService.Candidate candidate) {}
        List<Match> matched = new ArrayList<>();
        for (String slug : slugs) {
            String year = parser.extractYearFromSlug(slug);
            if (year == null) continue;

            List<SynopsisDatabaseService.Candidate> candidatesForYear = byYear.get(year);
            if (candidatesForYear == null || candidatesForYear.isEmpty()) continue;

            String slugNorm = normalize(parser.extractNameFromSlug(slug));
            SynopsisDatabaseService.Candidate best = null;
            double bestScore = 0;
            for (SynopsisDatabaseService.Candidate cand : candidatesForYear) {
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
        log(String.format("[Sacnilk] Matched %,d movie(s) needing a synopsis to sacnilk slugs.", matched.size()));

        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;
        Set<SynopsisDatabaseService.Candidate> filledSet  = new HashSet<>();
        Set<SynopsisDatabaseService.Candidate> erroredSet = new HashSet<>();

        try (SynopsisDatabaseService db = new SynopsisDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (Match m : matched) {
                String slug = m.slug();
                SynopsisDatabaseService.Candidate c = m.candidate();

                throttle(lastRequestAt, delayMs);

                try {
                    String synopsis = parser.fetchSynopsis(slug);
                    lastRequestAt = System.currentTimeMillis();

                    if (synopsis == null || synopsis.isBlank()) {
                        noData++;
                        continue;
                    }

                    int rows = db.updateSynopsisIfMissing(c.movieName(), c.year(), synopsis);
                    if (rows > 0) {
                        filled++;
                        filledSet.add(c);
                        log(String.format("[Sacnilk] Updated '%-45s' (%s) | %s",
                            c.movieName(), c.year(), truncateForLog(synopsis)));
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
                    logErr(String.format("[Sacnilk] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        // "No data" for the recheck cooldown = every original candidate that wasn't filled and
        // didn't error — this includes both sacnilk-matched-but-empty movies AND movies sacnilk's
        // sitemap had no slug for at all (the latter never even entered the `matched` loop above).
        List<SynopsisDatabaseService.Candidate> reallyNoData = new ArrayList<>();
        for (SynopsisDatabaseService.Candidate c : candidates) {
            if (!filledSet.contains(c) && !erroredSet.contains(c)) reallyNoData.add(c);
        }

        log(String.format("[Sacnilk] Done — filled: %,d | no synopsis found: %,d | errors: %,d (of %,d matched)",
            filled, noData, errors, matched.size()));
        return new PhaseResult(filled, reallyNoData);
    }

    private String truncateForLog(String synopsis) {
        String oneLine = synopsis.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 90 ? oneLine.substring(0, 90) + "..." : oneLine;
    }

    // ---- name normalisation and similarity (mirrors SacnilkCrawlerService) ----

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
    // detached background process with stdout redirected to a log file (e.g. `nohup ... &`),
    // and a redirected-to-file stdout is block-buffered rather than line-buffered, so without
    // this a live `tail -f` (or any progress check) can lag reality by a large, unpredictable
    // margin even though the underlying DB writes are already committed.
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
