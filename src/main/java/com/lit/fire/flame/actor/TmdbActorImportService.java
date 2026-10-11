package com.lit.fire.flame.actor;

import com.lit.fire.flame.moviedetails.TmdbClient;
import com.lit.fire.flame.moviedetails.TmdbMovieCredits;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One-shot import of the lead cast of every India-origin TMDB movie released from
 * actors.tmdb.year.from (or a given year range) to today into actors_data_collection (--actor-tmdb-import-once).
 *
 * For each movie (listed per year via /discover/movie, with_origin_country=IN) one request
 * returns its details, cast and crew; the top actors.tmdb.lead.count billed cast members each
 * get a row (role_position = billing position, 1 = top-billed), carrying the film's director(s)
 * and music director (see TmdbClient#musicDirectorsFrom) as columns. Each lead's birthday costs one more request, once per
 * person per run. Matching existing rows are updated with TMDB's values, the rest inserted —
 * see TmdbActorDatabaseService.
 *
 * Every TMDB request goes through TmdbClient's shared rate limiter
 * (tmdb.rate.limit.requests.per.second), so the worker pool size only affects how many
 * requests are in flight, never the request rate. Progress is checkpointed per movie, so an
 * interrupted run resumes where it stopped; a re-run after actors.tmdb.recheck.days
 * re-processes everything to pick up TMDB's newer data.
 */
public class TmdbActorImportService {

    private static final String PREFIX = "[ACTOR-TMDB] ";

    public void runOnce() throws Exception {
        runOnce(null, null);
    }

    /**
     * @param fromYearArg first release year to import, or null for actors.tmdb.year.from
     * @param toYearArg   last release year to import, or null for the current year (always capped at today)
     */
    public void runOnce(Integer fromYearArg, Integer toYearArg) throws Exception {
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

        int    tmdbTimeoutSec = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec   = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        int    concurrency    = Integer.parseInt(config.getProperty("actors.tmdb.concurrency", "20"));
        int    yearFrom       = fromYearArg != null ? fromYearArg
                                  : Integer.parseInt(config.getProperty("actors.tmdb.year.from", "2006"));
        int    leadCount      = Integer.parseInt(config.getProperty("actors.tmdb.lead.count", "3"));
        int    recheckDays    = Integer.parseInt(config.getProperty("actors.tmdb.recheck.days", "30"));

        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, tmdbTimeoutSec, 0.0, maxReqPerSec);
        LocalDate today = LocalDate.now();
        int yearTo = toYearArg != null ? Math.min(toYearArg, today.getYear()) : today.getYear();
        String recheckCutoff = today.minusDays(recheckDays).toString();
        Map<Integer, Optional<String>> birthdays = new ConcurrentHashMap<>();

        log(String.format("=== Starting TMDB lead-cast import: India-origin movies %d to %s, top %d billed, %.0f req/s cap ===",
            yearFrom, yearTo == today.getYear() ? today : yearTo, leadCount, maxReqPerSec));

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        Map<TmdbActorDatabaseService.SaveResult, Integer> totals = new HashMap<>();
        int totalMovies = 0, totalErrors = 0;
        try (TmdbActorDatabaseService db = new TmdbActorDatabaseService(dbUrl, dbUser, dbPassword)) {
            db.ensureSchema();

            for (int year = yearFrom; year <= yearTo; year++) {
                String lastDate = year == today.getYear() ? today.toString() : year + "-12-31";
                List<Integer> ids;
                try {
                    ids = tmdb.discoverIndianMovieIds(year, lastDate);
                } catch (Exception e) {
                    logErr(String.format("%d: failed to discover movies: %s — skipping year.", year, e.getMessage()));
                    totalErrors++;
                    continue;
                }
                Set<Integer> done = db.moviesCheckedSince(ids, recheckCutoff);
                List<Integer> pending = ids.stream().filter(id -> !done.contains(id)).toList();
                log(String.format("%d: %,d India-origin movie(s), %,d already imported.", year, ids.size(), done.size()));
                if (pending.isEmpty()) continue;

                CompletionService<MovieOutcome> cs = new ExecutorCompletionService<>(executor);
                for (int id : pending) {
                    cs.submit(() -> fetchSafely(tmdb, id, leadCount, birthdays));
                }

                Map<TmdbActorDatabaseService.SaveResult, Integer> yearCounts = new HashMap<>();
                int errors = 0;
                for (int completed = 1; completed <= pending.size(); completed++) {
                    MovieOutcome o = cs.take().get();
                    if (o.error() != null) {
                        errors++;
                        logErr(String.format("%d: [%d/%d] movie %d — error: %s",
                            year, completed, pending.size(), o.movieId(), o.error().getMessage()));
                        continue;
                    }
                    TmdbMovieCredits m = o.movie();
                    try {
                        if (m != null && m.releaseDate().compareTo(today.toString()) <= 0) {
                            for (TmdbMovieCredits.Lead lead : m.leads()) {
                                String birthday = birthdays.getOrDefault(lead.personId(), Optional.empty()).orElse(null);
                                yearCounts.merge(db.saveLead(m, lead, birthday), 1, Integer::sum);
                            }
                        }
                        db.markMovie(o.movieId(), today.toString());
                    } catch (Exception e) {
                        errors++;
                        logErr(String.format("%d: [%d/%d] '%s' — save error: %s",
                            year, completed, pending.size(), m.title(), e.getMessage()));
                    }
                }

                yearCounts.forEach((k, v) -> totals.merge(k, v, Integer::sum));
                totalMovies += pending.size() - errors;
                totalErrors += errors;
                log(String.format("%d: complete — %,d movie(s) | rows inserted: %,d | updated: %,d | unchanged: %,d | " +
                        "PK conflicts: %,d | errors: %,d",
                    year, pending.size() - errors, count(yearCounts, TmdbActorDatabaseService.SaveResult.INSERTED),
                    count(yearCounts, TmdbActorDatabaseService.SaveResult.UPDATED),
                    count(yearCounts, TmdbActorDatabaseService.SaveResult.UNCHANGED),
                    count(yearCounts, TmdbActorDatabaseService.SaveResult.CONFLICT), errors));
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        log(String.format("=== Import complete — movies: %,d | rows inserted: %,d | updated: %,d | unchanged: %,d | " +
                "PK conflicts: %,d | errors: %,d ===",
            totalMovies, count(totals, TmdbActorDatabaseService.SaveResult.INSERTED),
            count(totals, TmdbActorDatabaseService.SaveResult.UPDATED),
            count(totals, TmdbActorDatabaseService.SaveResult.UNCHANGED),
            count(totals, TmdbActorDatabaseService.SaveResult.CONFLICT), totalErrors));
    }

    /** One movie's fetch result — captured on the worker thread, never thrown across threads. */
    private record MovieOutcome(int movieId, TmdbMovieCredits movie, Exception error) {}

    /** Fetches the movie, then any of its leads' birthdays not already cached this run. */
    private static MovieOutcome fetchSafely(TmdbClient tmdb, int movieId, int leadCount,
                                            Map<Integer, Optional<String>> birthdays) {
        try {
            TmdbMovieCredits m = tmdb.fetchMovieCredits(movieId, leadCount);
            if (m != null) {
                for (TmdbMovieCredits.Lead lead : m.leads()) {
                    if (!birthdays.containsKey(lead.personId())) {
                        birthdays.put(lead.personId(), Optional.ofNullable(tmdb.fetchBirthday(lead.personId())));
                    }
                }
            }
            return new MovieOutcome(movieId, m, null);
        } catch (Exception e) {
            return new MovieOutcome(movieId, null, e);
        }
    }

    private static int count(Map<TmdbActorDatabaseService.SaveResult, Integer> counts, TmdbActorDatabaseService.SaveResult r) {
        return counts.getOrDefault(r, 0);
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
