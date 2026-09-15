package com.lit.fire.flame.indiamovies;

import com.lit.fire.flame.moviedetails.IndiaMovieRecord;
import com.lit.fire.flame.moviedetails.TmdbClient;

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
import java.util.concurrent.TimeUnit;

/**
 * One-shot catalog import: reads every India-origin movie TMDB has on file released between
 * {@code indiamovies.year.from} and {@code indiamovies.year.to} (inclusive) that has both a
 * non-zero budget AND a non-zero revenue on record, and saves it into india_movies_data_collection
 * (see IndiaMoviesDatabaseService — created as an exact schema mirror of movies_data_collection,
 * keyed by the same (movie_name, release_date, language) primary key).
 *
 * Unlike MovieDetailsService (which enriches rows already sitting in movies_data_collection by
 * fuzzy-matching this app's own movie_name/year to a TMDB search result), this discovers
 * candidates directly from TMDB's own catalog via /discover/movie — there's no matching
 * ambiguity to resolve here, since every discovered ID unambiguously names one specific TMDB
 * movie. budget/revenue aren't filterable through discover itself, so every discovered ID still
 * needs its own details call to check them — see TmdbClient#fetchIndiaMovieRecord.
 *
 * Intentionally a manual, one-shot command (--india-movies-import-once) rather than a repeating
 * daemon: this is a bounded, one-time catalog scan across a fixed year range, not a growing
 * backlog that needs periodic rechecking like this app's other TMDB-backed services.
 */
public class IndiaMoviesImportService {

    private static final String PREFIX = "[INDIA-MOVIES] ";

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String bearerToken = secrets.getProperty("tmdb.api.read.access.token", "");
        String apiKeyV3     = secrets.getProperty("tmdb.api.key", "");
        if (bearerToken.isBlank() && apiKeyV3.isBlank()) {
            logErr("Neither tmdb.api.read.access.token nor tmdb.api.key is set in secrets.properties — aborting.");
            return;
        }

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String sourceTable = config.getProperty("table.name", "movies_data_collection");
        String targetTable = config.getProperty("indiamovies.target.table", "india_movies_data_collection");

        int    tmdbTimeoutSec = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec   = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        int    concurrency    = Integer.parseInt(config.getProperty("indiamovies.concurrency", "20"));
        int    yearFrom       = Integer.parseInt(config.getProperty("indiamovies.year.from", "2006"));
        int    yearTo         = Integer.parseInt(config.getProperty("indiamovies.year.to", "2026"));

        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, tmdbTimeoutSec, 0.0, maxReqPerSec);

        try (IndiaMoviesDatabaseService db = new IndiaMoviesDatabaseService(dbUrl, dbUser, dbPassword, sourceTable, targetTable)) {
            if (!db.tableExists(sourceTable)) {
                logErr("Source table '" + sourceTable + "' does not exist — aborting.");
                return;
            }
            db.ensureTableExists();
        }

        log(String.format("=== Starting India movies catalog import: %d-%d, budget+revenue required, target table '%s' ===",
            yearFrom, yearTo, targetTable));

        String today = LocalDate.now().toString();
        int totalDiscovered = 0, totalSaved = 0, totalSkippedNoData = 0, totalErrors = 0;

        for (int year = yearFrom; year <= yearTo; year++) {
            List<Integer> ids;
            try {
                ids = tmdb.discoverIndianMovieIds(year);
            } catch (Exception e) {
                logErr(String.format("%d: failed to discover candidates: %s — skipping year.", year, e.getMessage()));
                totalErrors++;
                continue;
            }
            totalDiscovered += ids.size();
            log(String.format("%d: found %,d India-origin movie(s) on TMDB.", year, ids.size()));
            if (ids.isEmpty()) continue;

            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CompletionService<FetchOutcome> completionService = new ExecutorCompletionService<>(executor);
            for (int id : ids) {
                completionService.submit(fetchSafely(tmdb, id));
            }

            int saved = 0, skipped = 0, errors = 0;
            try (IndiaMoviesDatabaseService db = new IndiaMoviesDatabaseService(dbUrl, dbUser, dbPassword, sourceTable, targetTable)) {
                for (int completed = 1; completed <= ids.size(); completed++) {
                    FetchOutcome outcome;
                    try {
                        outcome = completionService.take().get();
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                        throw e;
                    }

                    if (outcome.error() != null) {
                        errors++;
                        db.rollback();
                        logErr(String.format("%d: [%d/%d] TMDB id %d — error: %s",
                            year, completed, ids.size(), outcome.tmdbId(), outcome.error().getMessage()));
                        continue;
                    }
                    IndiaMovieRecord rec = outcome.record();
                    if (rec == null) {
                        skipped++;
                        continue;
                    }
                    try {
                        db.upsert(rec, today);
                        saved++;
                        log(String.format("%d: [%d/%d] '%s' (%s, %s) — budget=%,d revenue=%,d rating=%s runtime=%s",
                            year, completed, ids.size(), rec.movieName(), rec.releaseDate(), rec.language(),
                            rec.budget(), rec.revenue(), rec.rating(), rec.runtimeMinutes()));
                    } catch (Exception e) {
                        errors++;
                        db.rollback();
                        logErr(String.format("%d: [%d/%d] '%s' — save error: %s",
                            year, completed, ids.size(), rec.movieName(), e.getMessage()));
                    }
                }
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            }

            totalSaved += saved;
            totalSkippedNoData += skipped;
            totalErrors += errors;
            log(String.format("%d: complete — saved: %,d | no budget/revenue: %,d | errors: %,d",
                year, saved, skipped, errors));
        }

        log(String.format(
            "=== Import complete — years: %d-%d | discovered: %,d | saved: %,d | no budget/revenue: %,d | errors: %,d ===",
            yearFrom, yearTo, totalDiscovered, totalSaved, totalSkippedNoData, totalErrors));
    }

    /** One TMDB details-fetch result — captured on the worker thread, never thrown across threads. */
    private record FetchOutcome(int tmdbId, IndiaMovieRecord record, Exception error) {}

    private static Callable<FetchOutcome> fetchSafely(TmdbClient tmdb, int tmdbId) {
        return () -> {
            try {
                return new FetchOutcome(tmdbId, tmdb.fetchIndiaMovieRecord(tmdbId), null);
            } catch (Exception e) {
                return new FetchOutcome(tmdbId, null, e);
            }
        };
    }

    // ---- helpers ----

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
