package com.lit.fire.flame.runtimebudget;

import com.lit.fire.flame.crawler.BoxOfficeMojoParser;
import com.lit.fire.flame.crawler.BoxOfficeRecord;
import com.lit.fire.flame.crawler.ExchangeRateService;
import com.lit.fire.flame.crawler.KoimoiParser;
import com.lit.fire.flame.crawler.SacnilkHtmlParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Background service that fills the "runtime" and "budget" columns in
 * movies_data_collection, in priority order from six sources:
 *
 *   1. sacnilk.com         – runtime (minutes) + budget (INR Crore → USD)
 *   2. boxofficeindex.in   – runtime + budget (INR Crore → USD); small catalog, recent titles only
 *   3. cinefry.co.in       – runtime (rarely populated) + budget (INR Crore → USD)
 *   4. tenvow.com          – runtime (on richer articles only) + budget (INR Crore → USD)
 *   5. boxofficemojo.com   – budget only, native USD (fills gaps after the above)
 *   6. koimoi.com          – budget only (INR Crore → USD; final gap-fill)
 *
 * Only sacnilk/boxofficeindex/cinefry/tenvow's detail pages expose a runtime field — BOM and
 * Koimoi's box-office pages/articles carry gross/budget figures but no runtime, so phases 5
 * and 6 only ever gap-fill "budget", mirroring BoxOfficeCrawlerOrchestrator's revenue/budget
 * gap-fill priority and its per-source politeness delays/robots.txt checks.
 *
 * Scope: Indian-language movies released after 2000 whose runtime or budget is currently 0,
 * matched to each source the same way CreditsCrawlerService/BoxOfficeCrawlerOrchestrator do
 * (year + fuzzy title match where the source's URLs carry a year; name-only fuzzy match for
 * cinefry/tenvow, whose slugs/search results don't reliably carry one).
 *
 * Thread model mirrors CreditsCrawlerService: run() loops forever with a configurable
 * interval (default 24h); runOnce() does a single cycle for one-shot/manual invocation
 * (--runtime-budget-scan-once).
 */
public class RuntimeBudgetCrawlerService implements Runnable {

    private static final String PREFIX = "[RUNTIME-BUDGET] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("runtimebudget.enabled", "true"))) {
            log("Disabled via runtimebudget.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("runtimebudget.initial.delay.ms", "27000"));
        long intervalMs     = Long.parseLong(config.getProperty("runtimebudget.interval.hours",   "24")) * 3_600_000L;

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
     * Intended for the {@code --runtime-budget-scan-once} CLI mode.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    // ---- cycle ----

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting runtime/budget enrichment cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        int    batchSize    = Integer.parseInt(config.getProperty("runtimebudget.candidate.batch.size", "3000"));
        double threshold    = Double.parseDouble(config.getProperty("runtimebudget.match.threshold",    "0.70"));
        long   sacnilkDelay = Long.parseLong(config.getProperty("runtimebudget.sacnilk.delay.ms", "1500"));
        int    recheckDays  = Integer.parseInt(config.getProperty("runtimebudget.recheck.interval.days", "14"));
        boolean boiEnabled     = Boolean.parseBoolean(config.getProperty("runtimebudget.boxofficeindex.enabled", "true"));
        long    boiDelay       = Long.parseLong(config.getProperty("runtimebudget.boxofficeindex.delay.ms",      "1500"));
        boolean cinefryEnabled = Boolean.parseBoolean(config.getProperty("runtimebudget.cinefry.enabled",  "true"));
        long    cinefryDelay   = Long.parseLong(config.getProperty("runtimebudget.cinefry.delay.ms",       "1500"));
        boolean tenvowEnabled  = Boolean.parseBoolean(config.getProperty("runtimebudget.tenvow.enabled",   "true"));
        long    tenvowDelay    = Long.parseLong(config.getProperty("runtimebudget.tenvow.delay.ms",        "2000"));
        boolean bomEnabled     = Boolean.parseBoolean(config.getProperty("runtimebudget.bom.enabled",    "true"));
        long    bomDelay       = Long.parseLong(config.getProperty("runtimebudget.bom.delay.ms",         "2000"));
        boolean koimoiEnabled  = Boolean.parseBoolean(config.getProperty("runtimebudget.koimoi.enabled", "true"));
        long    koimoiDelay    = Long.parseLong(config.getProperty("runtimebudget.koimoi.delay.ms",      "2000"));

        List<RuntimeBudgetDatabaseService.Candidate> candidates;
        ExchangeRateService exchangeRate = new ExchangeRateService();
        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                log("Table '" + tableName + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnsExist();
            db.ensureRateTableExists();
            candidates = db.getMoviesMissingRuntimeBudget(batchSize, recheckDays);

            Map<String, Double> existingRates = db.getExistingRates("INR", "USD");
            exchangeRate.preloadCache(existingRates);
            log(String.format("Pre-loaded %,d exchange rate(s) from currency_rate_xe.", existingRates.size()));
        }
        log(String.format(
            "Found %,d Indian-language movie(s) missing runtime/budget data (released after 2000, most recent year first).",
            candidates.size()));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible movie already has both fields, or is within its recheck cooldown.");
            return;
        }

        // Tracks, across all six phases, which candidates ended up with each field filled —
        // used both to skip the recheck cooldown (only when every originally-needed field for
        // that candidate got filled) and to scope each later phase to movies still missing
        // something that phase's source can actually supply.
        Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled = new HashSet<>();
        Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled  = new HashSet<>();
        Set<RuntimeBudgetDatabaseService.Candidate> erroredSet    = new HashSet<>();

        log("=== Phase 1/6 — sacnilk.com (runtime + budget) ===");
        int[] sacnilkStats = runSacnilkPhase(dbUrl, dbUser, dbPassword, tableName,
            candidates, exchangeRate, threshold, sacnilkDelay, runtimeFilled, budgetFilled, erroredSet);

        List<RuntimeBudgetDatabaseService.Candidate> stillNeedAnything =
            stillNeedingAnything(candidates, runtimeFilled, budgetFilled, erroredSet);

        int[] boiStats = {0, 0, 0};
        if (boiEnabled && !stillNeedAnything.isEmpty()) {
            log("=== Phase 2/6 — boxofficeindex.in (runtime + budget gap-fill) ===");
            if (isPathAllowed("https://boxofficeindex.in", "/movie/")) {
                boiStats = runBoxOfficeIndexPhase(dbUrl, dbUser, dbPassword, tableName,
                    stillNeedAnything, exchangeRate, threshold, boiDelay, runtimeFilled, budgetFilled, erroredSet);
            } else {
                log("[BoxOfficeIndex] Skipping — /movie/ disallowed by robots.txt.");
            }
        }

        stillNeedAnything = stillNeedingAnything(candidates, runtimeFilled, budgetFilled, erroredSet);

        int[] cinefryStats = {0, 0, 0};
        if (cinefryEnabled && !stillNeedAnything.isEmpty()) {
            log("=== Phase 3/6 — cinefry.co.in (runtime + budget gap-fill) ===");
            if (isPathAllowed("https://www.cinefry.co.in", "/sitemap_index.xml")) {
                cinefryStats = runCinefryPhase(dbUrl, dbUser, dbPassword, tableName,
                    stillNeedAnything, exchangeRate, threshold, cinefryDelay, runtimeFilled, budgetFilled, erroredSet);
            } else {
                log("[Cinefry] Skipping — sitemap disallowed by robots.txt.");
            }
        }

        stillNeedAnything = stillNeedingAnything(candidates, runtimeFilled, budgetFilled, erroredSet);

        int[] tenvowStats = {0, 0, 0};
        if (tenvowEnabled && !stillNeedAnything.isEmpty()) {
            log("=== Phase 4/6 — tenvow.com (runtime + budget gap-fill) ===");
            if (isPathAllowed("https://tenvow.com", "/box-office/")) {
                tenvowStats = runTenvowPhase(dbUrl, dbUser, dbPassword, tableName,
                    stillNeedAnything, exchangeRate, threshold, tenvowDelay, runtimeFilled, budgetFilled, erroredSet);
            } else {
                log("[Tenvow] Skipping — /box-office/ disallowed by robots.txt.");
            }
        }

        // Only movies still missing budget can benefit from BOM/Koimoi — neither source
        // exposes a runtime field.
        List<RuntimeBudgetDatabaseService.Candidate> stillNeedBudget = candidates.stream()
            .filter(c -> c.needsBudget() && !budgetFilled.contains(c) && !erroredSet.contains(c))
            .toList();

        int[] bomStats = {0, 0, 0};
        if (bomEnabled && !stillNeedBudget.isEmpty()) {
            log("=== Phase 5/6 — boxofficemojo.com (budget gap-fill) ===");
            if (isPathAllowed("https://www.boxofficemojo.com", "/search/")) {
                bomStats = runBomPhase(dbUrl, dbUser, dbPassword, tableName,
                    stillNeedBudget, threshold, bomDelay, budgetFilled, erroredSet);
            } else {
                log("[BOM] Skipping — /search/ disallowed by robots.txt.");
            }
        }

        List<RuntimeBudgetDatabaseService.Candidate> stillNeedBudgetAfterBom = candidates.stream()
            .filter(c -> c.needsBudget() && !budgetFilled.contains(c) && !erroredSet.contains(c))
            .toList();

        int[] koimoiStats = {0, 0, 0};
        if (koimoiEnabled && !stillNeedBudgetAfterBom.isEmpty()) {
            log("=== Phase 6/6 — koimoi.com (budget gap-fill) ===");
            if (isPathAllowed("https://www.koimoi.com", "/")) {
                koimoiStats = runKoimoiPhase(dbUrl, dbUser, dbPassword, tableName,
                    stillNeedBudgetAfterBom, exchangeRate, threshold, koimoiDelay, budgetFilled, erroredSet);
            } else {
                log("[Koimoi] Skipping — crawling disallowed by robots.txt.");
            }
        }

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            // Candidates where every originally-needed field ended up filled, or that errored
            // out, are excluded — the former have nothing left to check, the latter should be
            // retried on the very next cycle rather than waiting out the cooldown. Everyone
            // else is marked checked-today so the next cycle's batch moves on to fresh
            // candidates instead of retrying these forever; picked up again automatically
            // after runtimebudget.recheck.interval.days.
            String today = LocalDate.now().toString();
            for (RuntimeBudgetDatabaseService.Candidate c : candidates) {
                boolean fullySatisfied =
                    (!c.needsRuntime() || runtimeFilled.contains(c)) &&
                    (!c.needsBudget()  || budgetFilled.contains(c));
                if (!fullySatisfied && !erroredSet.contains(c)) {
                    db.markChecked(c.movieName(), c.year(), today);
                }
            }

            // Persist newly fetched exchange rates so future cycles don't re-hit xe.com.
            Map<String, Double> newRates = exchangeRate.getNewlyFetchedRates();
            if (!newRates.isEmpty()) {
                for (Map.Entry<String, Double> entry : newRates.entrySet()) {
                    db.upsertExchangeRate(entry.getKey(), "INR", "USD", entry.getValue());
                }
                log(String.format("Saved %,d new exchange rate(s) to currency_rate_xe.", newRates.size()));
            }
        }

        int totalFilled = sacnilkStats[0] + boiStats[0] + cinefryStats[0] + tenvowStats[0]
            + bomStats[0] + koimoiStats[0];
        log(String.format(
            "=== Cycle complete — %,d candidate(s) considered | sacnilk: filled %,d/matched %,d | " +
            "BoxOfficeIndex: filled %,d/matched %,d | Cinefry: filled %,d/matched %,d | " +
            "Tenvow: filled %,d/tried %,d | BOM: filled %,d/tried %,d | Koimoi: filled %,d/tried %,d | " +
            "total filled: %,d (rechecked in %d day(s)) ===",
            candidates.size(), sacnilkStats[0], sacnilkStats[2],
            boiStats[0], boiStats[2], cinefryStats[0], cinefryStats[2], tenvowStats[0], tenvowStats[2],
            bomStats[0], bomStats[2], koimoiStats[0], koimoiStats[2],
            totalFilled, recheckDays));
    }

    /** Candidates still missing a field that at least one remaining source could supply. */
    private static List<RuntimeBudgetDatabaseService.Candidate> stillNeedingAnything(
            List<RuntimeBudgetDatabaseService.Candidate> candidates,
            Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled,
            Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
            Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) {
        return candidates.stream()
            .filter(c -> !erroredSet.contains(c))
            .filter(c -> (c.needsRuntime() && !runtimeFilled.contains(c)) ||
                         (c.needsBudget()  && !budgetFilled.contains(c)))
            .toList();
    }

    // ---- Phase 1: sacnilk.com (runtime + budget) ----

    /** @return {filled, noData+errors placeholder unused, matchedCount} — see call site for field meaning. */
    private int[] runSacnilkPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                   List<RuntimeBudgetDatabaseService.Candidate> candidates,
                                   ExchangeRateService exchangeRate, double threshold, long delayMs,
                                   Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled,
                                   Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                                   Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        SacnilkHtmlParser parser = new SacnilkHtmlParser();

        log("Fetching movie list from sacnilk sitemap...");
        List<String> slugs = parser.fetchMovieSlugsFromSitemap();
        log(String.format("Found %,d movie slug(s).", slugs.size()));
        Thread.sleep(delayMs);

        // Group candidates by year for matching, same approach as CreditsCrawlerService.
        Map<String, List<RuntimeBudgetDatabaseService.Candidate>> byYear = new HashMap<>();
        for (RuntimeBudgetDatabaseService.Candidate c : candidates) {
            byYear.computeIfAbsent(c.year(), k -> new ArrayList<>()).add(c);
        }

        record Match(String slug, RuntimeBudgetDatabaseService.Candidate candidate) {}
        List<Match> matched = new ArrayList<>();
        for (String slug : slugs) {
            String year = parser.extractYearFromSlug(slug);
            if (year == null) continue;

            List<RuntimeBudgetDatabaseService.Candidate> candidatesForYear = byYear.get(year);
            if (candidatesForYear == null || candidatesForYear.isEmpty()) continue;

            String slugNorm = normalize(parser.extractNameFromSlug(slug));
            RuntimeBudgetDatabaseService.Candidate best = null;
            double bestScore = 0;
            for (RuntimeBudgetDatabaseService.Candidate cand : candidatesForYear) {
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
        log(String.format("[sacnilk] Matched %,d movie(s) needing runtime/budget data to sacnilk slugs.", matched.size()));

        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (Match m : matched) {
                String slug = m.slug();
                RuntimeBudgetDatabaseService.Candidate c = m.candidate();

                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.parseMovieDetailPage(slug);
                    lastRequestAt = System.currentTimeMillis();

                    Integer runtimeMinutes = rec.runtimeMinutes();
                    Double  budgetCr       = rec.budgetCr();

                    if (runtimeMinutes == null && budgetCr == null) {
                        noData++;
                        continue;
                    }

                    Long budgetUsd = null;
                    if (budgetCr != null) {
                        double inrUsdRate = exchangeRate.getInrToUsdRate(c.releaseDate());
                        budgetUsd = exchangeRate.inrCroreToUsd(budgetCr, inrUsdRate);
                    }

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), runtimeMinutes, budgetUsd);
                    if (rows > 0) {
                        filled++;
                        if (runtimeMinutes != null) runtimeFilled.add(c);
                        if (budgetUsd      != null) budgetFilled.add(c);
                        log(String.format("[sacnilk] Updated '%-45s' (%s) | runtime: %s | budget: %s",
                            c.movieName(), c.year(),
                            runtimeMinutes != null ? runtimeMinutes + " min" : "n/a",
                            budgetUsd      != null ? "$" + String.format("%,d", budgetUsd) : "n/a"));
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
                    logErr(String.format("[sacnilk] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        log(String.format("[sacnilk] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, matched.size(), errors};
    }

    // ---- Phase 2: boxofficeindex.in (runtime + budget) ----

    private int[] runBoxOfficeIndexPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                          List<RuntimeBudgetDatabaseService.Candidate> candidates,
                                          ExchangeRateService exchangeRate, double threshold, long delayMs,
                                          Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled,
                                          Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                                          Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        BoxOfficeIndexParser parser = new BoxOfficeIndexParser();

        log("[BoxOfficeIndex] Fetching movie list from sitemap...");
        List<String> slugs = parser.fetchMovieSlugsFromSitemap();
        log(String.format("[BoxOfficeIndex] Found %,d movie slug(s).", slugs.size()));
        Thread.sleep(delayMs);

        Map<String, List<RuntimeBudgetDatabaseService.Candidate>> byYear = new HashMap<>();
        for (RuntimeBudgetDatabaseService.Candidate c : candidates) {
            byYear.computeIfAbsent(c.year(), k -> new ArrayList<>()).add(c);
        }

        record Match(String slug, RuntimeBudgetDatabaseService.Candidate candidate) {}
        List<Match> matched = new ArrayList<>();
        for (String slug : slugs) {
            String year = parser.extractYearFromSlug(slug);
            if (year == null) continue;

            List<RuntimeBudgetDatabaseService.Candidate> candidatesForYear = byYear.get(year);
            if (candidatesForYear == null || candidatesForYear.isEmpty()) continue;

            String slugNorm = normalize(parser.extractNameFromSlug(slug));
            RuntimeBudgetDatabaseService.Candidate best = null;
            double bestScore = 0;
            for (RuntimeBudgetDatabaseService.Candidate cand : candidatesForYear) {
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
        log(String.format("[BoxOfficeIndex] Matched %,d movie(s) to boxofficeindex.in slugs.", matched.size()));

        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (Match m : matched) {
                RuntimeBudgetDatabaseService.Candidate c = m.candidate();
                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.parseMovieDetailPage(m.slug());
                    lastRequestAt = System.currentTimeMillis();

                    Integer runtimeMinutes = rec.runtimeMinutes();
                    Double  budgetCr       = rec.budgetCr();
                    if (runtimeMinutes == null && budgetCr == null) {
                        noData++;
                        continue;
                    }

                    Long budgetUsd = null;
                    if (budgetCr != null) {
                        double inrUsdRate = exchangeRate.getInrToUsdRate(c.releaseDate());
                        budgetUsd = exchangeRate.inrCroreToUsd(budgetCr, inrUsdRate);
                    }

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), runtimeMinutes, budgetUsd);
                    if (rows > 0) {
                        filled++;
                        if (runtimeMinutes != null) runtimeFilled.add(c);
                        if (budgetUsd      != null) budgetFilled.add(c);
                        log(String.format("[BoxOfficeIndex] Updated '%-45s' (%s) | runtime: %s | budget: %s",
                            c.movieName(), c.year(),
                            runtimeMinutes != null ? runtimeMinutes + " min" : "n/a",
                            budgetUsd      != null ? "$" + String.format("%,d", budgetUsd) : "n/a"));
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
                    logErr(String.format("[BoxOfficeIndex] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        log(String.format("[BoxOfficeIndex] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, matched.size(), errors};
    }

    // ---- Phase 3: cinefry.co.in (runtime + budget) ----

    private int[] runCinefryPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                   List<RuntimeBudgetDatabaseService.Candidate> candidates,
                                   ExchangeRateService exchangeRate, double threshold, long delayMs,
                                   Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled,
                                   Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                                   Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        CinefryParser parser = new CinefryParser();

        log("[Cinefry] Fetching financial-article list from sitemaps...");
        List<String> articleUrls = parser.fetchFinancialArticleUrls();
        log(String.format("[Cinefry] Found %,d financial article(s).", articleUrls.size()));
        Thread.sleep(delayMs);

        // cinefry slugs carry no release year, so matching is name-only against every
        // still-needy candidate regardless of year (same limitation as a year-less
        // Koimoi/BOM search result).
        record Match(String url, RuntimeBudgetDatabaseService.Candidate candidate) {}
        List<Match> matched = new ArrayList<>();
        for (String url : articleUrls) {
            String articleNorm = normalize(parser.extractNameFromUrl(url));
            RuntimeBudgetDatabaseService.Candidate best = null;
            double bestScore = 0;
            for (RuntimeBudgetDatabaseService.Candidate cand : candidates) {
                double score = similarity(articleNorm, normalize(cand.movieName()));
                if (score > bestScore) {
                    bestScore = score;
                    best      = cand;
                }
            }
            if (bestScore >= threshold) {
                matched.add(new Match(url, best));
            }
        }
        log(String.format("[Cinefry] Matched %,d movie(s) to cinefry articles.", matched.size()));

        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (Match m : matched) {
                RuntimeBudgetDatabaseService.Candidate c = m.candidate();
                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.parseArticlePage(m.url());
                    lastRequestAt = System.currentTimeMillis();

                    Integer runtimeMinutes = rec.runtimeMinutes();
                    Double  budgetCr       = rec.budgetCr();
                    if (runtimeMinutes == null && budgetCr == null) {
                        noData++;
                        continue;
                    }

                    Long budgetUsd = null;
                    if (budgetCr != null) {
                        double inrUsdRate = exchangeRate.getInrToUsdRate(c.releaseDate());
                        budgetUsd = exchangeRate.inrCroreToUsd(budgetCr, inrUsdRate);
                    }

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), runtimeMinutes, budgetUsd);
                    if (rows > 0) {
                        filled++;
                        if (runtimeMinutes != null) runtimeFilled.add(c);
                        if (budgetUsd      != null) budgetFilled.add(c);
                        log(String.format("[Cinefry] Updated '%-45s' (%s) | runtime: %s | budget: %s",
                            c.movieName(), c.year(),
                            runtimeMinutes != null ? runtimeMinutes + " min" : "n/a",
                            budgetUsd      != null ? "$" + String.format("%,d", budgetUsd) : "n/a"));
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
                    logErr(String.format("[Cinefry] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        log(String.format("[Cinefry] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, matched.size(), errors};
    }

    // ---- Phase 4: tenvow.com (runtime + budget, via live search) ----

    private int[] runTenvowPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                  List<RuntimeBudgetDatabaseService.Candidate> candidates,
                                  ExchangeRateService exchangeRate, double threshold, long delayMs,
                                  Set<RuntimeBudgetDatabaseService.Candidate> runtimeFilled,
                                  Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                                  Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        log(String.format("[Tenvow] %,d movie(s) still missing runtime/budget.", candidates.size()));

        TenvowParser parser = new TenvowParser();
        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (RuntimeBudgetDatabaseService.Candidate c : candidates) {
                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.searchAndParse(c.movieName(), c.year(), threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec == null) {
                        noData++;
                        continue;
                    }

                    Integer runtimeMinutes = rec.runtimeMinutes();
                    Double  budgetCr       = rec.budgetCr();
                    Long budgetUsd = null;
                    if (budgetCr != null) {
                        double inrUsdRate = exchangeRate.getInrToUsdRate(c.releaseDate());
                        budgetUsd = exchangeRate.inrCroreToUsd(budgetCr, inrUsdRate);
                    }

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), runtimeMinutes, budgetUsd);
                    if (rows > 0) {
                        filled++;
                        if (runtimeMinutes != null) runtimeFilled.add(c);
                        if (budgetUsd      != null) budgetFilled.add(c);
                        log(String.format("[Tenvow] Updated '%-45s' (%s) | runtime: %s | budget: %s",
                            c.movieName(), c.year(),
                            runtimeMinutes != null ? runtimeMinutes + " min" : "n/a",
                            budgetUsd      != null ? "$" + String.format("%,d", budgetUsd) : "n/a"));
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
                    logErr(String.format("[Tenvow] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                    // 403 means the site is blocking us outright; 503 (seen in practice) means
                    // it's rejecting/shedding load under this request pattern — either way,
                    // further search requests are unlikely to succeed. Scoped to the search
                    // endpoint itself (mirrors KoimoiParser's check) so a one-off failure
                    // fetching a single matched article doesn't abort the whole phase.
                    if (e.getMessage() != null && e.getMessage().contains("/?s=") &&
                            (e.getMessage().contains("HTTP 403") || e.getMessage().contains("HTTP 503"))) {
                        logErr("[Tenvow] Search endpoint blocked/unavailable — aborting phase.");
                        break;
                    }
                }
            }
        }

        log(String.format("[Tenvow] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, candidates.size(), errors};
    }

    // ---- Phase 5: boxofficemojo.com (budget-only gap-fill) ----

    private int[] runBomPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                               List<RuntimeBudgetDatabaseService.Candidate> stillNeedBudget,
                               double threshold, long delayMs,
                               Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                               Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        log(String.format("[BOM] %,d movie(s) still missing budget after sacnilk.", stillNeedBudget.size()));

        BoxOfficeMojoParser parser = new BoxOfficeMojoParser();
        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (RuntimeBudgetDatabaseService.Candidate c : stillNeedBudget) {
                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.searchAndParse(c.movieName(), c.year(), threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec == null || rec.budgetUsd() == null) {
                        noData++;
                        continue;
                    }

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), null, rec.budgetUsd());
                    if (rows > 0) {
                        filled++;
                        budgetFilled.add(c);
                        log(String.format("[BOM] Updated '%-45s' (%s) | budget: $%,d",
                            c.movieName(), c.year(), rec.budgetUsd()));
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
                    logErr(String.format("[BOM] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                }
            }
        }

        log(String.format("[BOM] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, stillNeedBudget.size(), errors};
    }

    // ---- Phase 6: koimoi.com (budget-only gap-fill) ----

    private int[] runKoimoiPhase(String dbUrl, String dbUser, String dbPassword, String tableName,
                                  List<RuntimeBudgetDatabaseService.Candidate> stillNeedBudget,
                                  ExchangeRateService exchangeRate, double threshold, long delayMs,
                                  Set<RuntimeBudgetDatabaseService.Candidate> budgetFilled,
                                  Set<RuntimeBudgetDatabaseService.Candidate> erroredSet) throws Exception {
        log(String.format("[Koimoi] %,d movie(s) still missing budget after sacnilk + BOM.", stillNeedBudget.size()));

        KoimoiParser parser = new KoimoiParser();
        int filled = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (RuntimeBudgetDatabaseService db = new RuntimeBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (RuntimeBudgetDatabaseService.Candidate c : stillNeedBudget) {
                throttle(lastRequestAt, delayMs);

                try {
                    BoxOfficeRecord rec = parser.searchAndParse(c.movieName(), c.year(), threshold);
                    lastRequestAt = System.currentTimeMillis();

                    if (rec == null || rec.budgetCr() == null) {
                        noData++;
                        continue;
                    }

                    double inrUsdRate = exchangeRate.getInrToUsdRate(c.releaseDate());
                    long budgetUsd = exchangeRate.inrCroreToUsd(rec.budgetCr(), inrUsdRate);

                    int rows = db.updateRuntimeBudgetIfMissing(c.movieName(), c.year(), null, budgetUsd);
                    if (rows > 0) {
                        filled++;
                        budgetFilled.add(c);
                        log(String.format("[Koimoi] Updated '%-45s' (%s) | rate=%.5f | budget: $%,d",
                            c.movieName(), c.year(), inrUsdRate, budgetUsd));
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
                    logErr(String.format("[Koimoi] Error for '%s' (%s): %s", c.movieName(), c.year(), e.getMessage()));
                    // 403 on the search endpoint means the site is blocking the crawler —
                    // no further search requests will succeed, so abort the phase.
                    if (e.getMessage() != null && e.getMessage().contains("HTTP 403")
                            && e.getMessage().contains("/?s=")) {
                        logErr("[Koimoi] Search endpoint blocked (403) — aborting phase.");
                        break;
                    }
                }
            }
        }

        log(String.format("[Koimoi] Done — filled: %,d | no data: %,d | errors: %,d", filled, noData, errors));
        return new int[]{filled, noData, stillNeedBudget.size(), errors};
    }

    /**
     * Fetches robots.txt for the site and returns true when the given path is
     * not disallowed for the wildcard (*) user-agent. Returns true (assume allowed) when
     * robots.txt cannot be fetched. Mirrors BoxOfficeCrawlerOrchestrator's check.
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

    // ---- name normalisation and similarity (mirrors CreditsCrawlerService/SacnilkCrawlerService) ----

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
