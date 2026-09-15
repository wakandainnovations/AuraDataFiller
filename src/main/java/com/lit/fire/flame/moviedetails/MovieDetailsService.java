package com.lit.fire.flame.moviedetails;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Background service that fills IMDb-grade movie facts — rating_10, runtime, genre, country,
 * production_companies, directors, cbfc_rating — in movies_data_collection from the real TMDB
 * (The Movie Database) API.
 *
 * This replaced an earlier AuraLLM-based version of the same service: asking a local LLM to
 * recall these facts from memory produced confident-sounding but occasionally wrong answers
 * (confirmed in practice — e.g. a wrong production company for a movie released after the
 * model's actual knowledge cutoff, even though the row's year-only release_date made that
 * cutoff violation impossible to catch from the DB alone). TMDB is a real, continuously
 * maintained structured database rather than a memorised guess, so once a row is confidently
 * matched to a TMDB movie (see TmdbClient), every field returned is a verifiable fact, not a
 * recollection — and unlike the LLM, TMDB has no knowledge-cutoff limitation, so every release
 * year is a valid candidate, not just ones from before some cutoff date.
 *
 * Every field targeted here already exists on the table (filled elsewhere by web crawlers or
 * CSV import for at least some rows — see MovieDetailsDatabaseService's javadoc) except
 * cbfc_rating, which currently has no other writer at all. This service only ever fills a field
 * that is still missing/zero on a given row; it never overwrites an existing value, regardless
 * of source (COALESCE/NULLIF at the DB layer — see MovieDetailsDatabaseService).
 *
 * "language" and "release_date" are part of the table's primary key and are deliberately never
 * written here — they're only ever read and passed to TmdbClient as identifying/disambiguating
 * context. A movie group is (movie_name, release year): the same physical film's rating/runtime/
 * genre/country/production companies/directors/CBFC certificate don't vary across its different
 * language-dub rows, so one TMDB lookup's answer per (movie_name, year) is applied to every row
 * in the group — mirroring CreditsDatabaseService's directors/production_companies backfill.
 *
 * Thread model mirrors CreditsCrawlerService/LanguageIdService: run() loops forever with a
 * configurable interval (default 24h); runOnce() does a single batch for one-shot/manual
 * invocation (--movie-details-scan-once).
 *
 * Within one cycle, candidate TMDB lookups run on a bounded thread pool
 * (moviedetails.concurrency) rather than one at a time: measured in practice, a single-threaded
 * loop only used ~2 of TMDB's ~35 requests/second budget, since almost all of its time was spent
 * blocked waiting on one HTTP round trip before starting the next. TmdbClient's own rate limiter
 * (see its javadoc) still caps actual request issuance regardless of pool size, so raising the
 * pool doesn't risk exceeding TMDB's limit — it just keeps the pipe full while requests are
 * in flight. Only the TMDB lookup itself is parallelised; every DB write still happens on this
 * method's own thread through its single JDBC connection (not thread-safe for concurrent use),
 * as each worker's result completes — see the CompletionService loop below.
 */
public class MovieDetailsService implements Runnable {

    private static final String PREFIX = "[MOVIE-DETAILS] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("moviedetails.enabled", "true"))) {
            log("Disabled via moviedetails.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("moviedetails.initial.delay.ms", "45000"));
        long intervalMs     = Long.parseLong(config.getProperty("moviedetails.interval.hours",   "24")) * 3_600_000L;

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

    /** Runs exactly one batch synchronously, then returns. For --movie-details-scan-once. */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting movie-details enrichment cycle ===");

        String bearerToken = secrets.getProperty("tmdb.api.read.access.token", "");
        String apiKeyV3     = secrets.getProperty("tmdb.api.key", "");
        if (bearerToken.isBlank() && apiKeyV3.isBlank()) {
            logErr("Neither tmdb.api.read.access.token nor tmdb.api.key is set in secrets.properties — skipping cycle.");
            return;
        }

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String moviesTable = config.getProperty("table.name", "movies_data_collection");

        int    tmdbTimeoutSec = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec   = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        double matchThreshold = Double.parseDouble(config.getProperty("tmdb.match.threshold", "0.70"));
        int    batchSize      = Integer.parseInt(config.getProperty("moviedetails.candidate.batch.size", "300"));
        int    recheckDays    = Integer.parseInt(config.getProperty("moviedetails.recheck.interval.days", "180"));
        int    concurrency    = Integer.parseInt(config.getProperty("moviedetails.concurrency", "20"));

        List<MovieDetailsDatabaseService.Candidate> candidates;
        try (MovieDetailsDatabaseService db = new MovieDetailsDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            if (!db.tableExists()) {
                log("Table '" + moviesTable + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnsExist();
            candidates = db.getCandidates(recheckDays, batchSize);
        }

        log(String.format("Found %,d movie group(s) awaiting movie-details enrichment.", candidates.size()));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible group was already checked within the recheck window.");
            return;
        }

        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, tmdbTimeoutSec, matchThreshold, maxReqPerSec);

        String today = LocalDate.now().toString();
        int filled = 0, noMatch = 0, errors = 0;

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CompletionService<LookupOutcome> completionService = new ExecutorCompletionService<>(executor);
        for (MovieDetailsDatabaseService.Candidate c : candidates) {
            completionService.submit(lookupSafely(tmdb, c));
        }

        try (MovieDetailsDatabaseService db = new MovieDetailsDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            for (int completed = 1; completed <= candidates.size(); completed++) {
                String progress = String.format("[%d/%d]", completed, candidates.size());
                LookupOutcome outcome;
                try {
                    outcome = completionService.take().get();
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw e;
                }
                MovieDetailsDatabaseService.Candidate c = outcome.candidate();

                try {
                    if (outcome.error() != null) throw outcome.error();

                    MovieDetails details = outcome.details();
                    if (details == null || details.isEmpty()) {
                        noMatch++;
                        db.markChecked(c.movieName(), c.year(), today);
                        log(String.format("%s '%-45s' (%s) — no confident TMDB match.",
                            progress, c.movieName(), c.year()));
                    } else {
                        int updated = db.updateDetailsIfMissing(c.movieName(), c.year(), details, today);
                        filled++;
                        log(String.format("%s '%-45s' (%s) — filled %d row(s): rating=%s runtime=%s genre=%s " +
                            "country=%s production_companies=%s directors=%s cbfc_rating=%s",
                            progress, c.movieName(), c.year(), updated,
                            details.rating(), details.runtimeMinutes(), details.genre(), details.country(),
                            details.productionCompanies(), details.directors(), details.cbfcRating()));
                    }
                } catch (Exception e) {
                    errors++;
                    db.rollback();
                    logErr(String.format("%s Error for '%s' (%s): %s", progress, c.movieName(), c.year(), e.getMessage()));
                }
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        }

        log(String.format(
            "=== Cycle complete — candidates: %,d | filled: %,d | no confident match: %,d | errors: %,d " +
            "(rechecked in %d day(s)) ===", candidates.size(), filled, noMatch, errors, recheckDays));
    }

    /** One candidate's TMDB lookup result — a worker thread's result, never thrown across threads. */
    private record LookupOutcome(MovieDetailsDatabaseService.Candidate candidate, MovieDetails details, Exception error) {}

    /**
     * Runs one candidate's TMDB lookup on a worker thread, capturing any exception into the
     * result instead of letting it propagate — keeps the CompletionService loop above simple
     * (every future completes normally) and lets the caller log/handle the error against the
     * right candidate, same as the old single-threaded try/catch did per iteration.
     */
    private static Callable<LookupOutcome> lookupSafely(TmdbClient tmdb, MovieDetailsDatabaseService.Candidate c) {
        return () -> {
            try {
                return new LookupOutcome(c, tmdb.lookup(c.movieName(), c.year(), c.languages()), null);
            } catch (Exception e) {
                return new LookupOutcome(c, null, e);
            }
        };
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
