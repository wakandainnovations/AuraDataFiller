package com.lit.fire.flame.youtube;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Background service that searches YouTube for each movie's official trailer,
 * teaser, and first-song/single video, then writes their publish dates,
 * days-before-release, view counts, and comment counts back to the movies table.
 *
 * Scope: movies released after 2010, most recently released first.
 *
 * Quota reality: YouTube Data API v3's search.list costs 100 units per call and
 * the default daily quota is 10 000 units. Each movie needs up to 3 searches
 * (trailer/teaser/song) = up to 300 units, plus 1 unit for a batched videos.list
 * statistics call — so at the default quota this processes roughly 30 movies per
 * cycle. This is expected and by design; see youtube.daily.quota.units below.
 *
 * Thread model mirrors SacnilkCrawlerService: run() loops forever with a
 * configurable interval (default 24 h); runOnce() does a single cycle for
 * one-shot/manual invocation.
 */
public class YoutubeEnrichmentService implements Runnable {

    private static final String PREFIX = "[YOUTUBE] ";

    /** How many search results per query to consider before giving up on a category. */
    private static final int SEARCH_RESULTS_PER_QUERY = 5;

    private long unitsUsedThisCycle;

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("youtube.enabled", "true"))) {
            log("Disabled via youtube.enabled=false — exiting.");
            return;
        }

        long initialDelayMs  = Long.parseLong(config.getProperty("youtube.initial.delay.ms", "10000"));
        long intervalMs      = Long.parseLong(config.getProperty("youtube.interval.hours", "24")) * 3_600_000L;
        long quotaRetryMs    = Long.parseLong(config.getProperty("youtube.quota.retry.hours", "3")) * 3_600_000L;

        sleep(initialDelayMs, "initial startup delay");

        while (!Thread.currentThread().isInterrupted()) {
            boolean quotaExceeded = false;
            try {
                // There's no API to ask Google "how much quota is left" up front — the only
                // reliable signal is a real call succeeding or failing with a quota-exceeded
                // response. So this doubles as the quota check: if the very first search of the
                // cycle fails that way, quota isn't available right now; if it succeeds, quota
                // exists and the cycle proceeds through the full post-2010 candidate backlog
                // (bounded by youtube.daily.quota.units) exactly as it would otherwise.
                quotaExceeded = runCycle(secrets, config);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logErr("Cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }

            if (quotaExceeded) {
                log(String.format("YouTube API quota not available — retrying in %d hour(s) " +
                    "instead of waiting for the normal %d-hour cycle interval.",
                    quotaRetryMs / 3_600_000L, intervalMs / 3_600_000L));
                if (!sleep(quotaRetryMs, "quota retry wait")) break;
            } else {
                log(String.format("Next cycle in %d hour(s). Sleeping...", intervalMs / 3_600_000L));
                if (!sleep(intervalMs, "inter-cycle interval")) break;
            }
        }
        log("Service stopped.");
    }

    /**
     * Runs exactly one cycle synchronously, then returns.
     * Intended for the {@code --youtube-scan-once} CLI mode where the JVM should exit after.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        if (runCycle(secrets, config)) {
            logErr("YouTube API quota was not available during this run — nothing further could " +
                "be processed. Try again later (or use --youtube-scan, which retries automatically).");
        }
    }

    // ---- cycle ----

    /** @return true if the cycle stopped early because YouTube API quota wasn't available. */
    private boolean runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting YouTube promo-metrics enrichment cycle ===");

        String apiKey = secrets.getProperty("youtube.api.key", "");
        if (apiKey.isBlank()) {
            logErr("youtube.api.key is not set in secrets.properties — skipping cycle.");
            return false;
        }

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        long dailyQuotaUnits  = Long.parseLong(config.getProperty("youtube.daily.quota.units", "10000"));
        long requestDelayMs   = Long.parseLong(config.getProperty("youtube.request.delay.ms", "350"));
        int candidateBatch    = Integer.parseInt(config.getProperty("youtube.candidate.batch.size", "500"));
        int promoWindowYears  = Integer.parseInt(config.getProperty("youtube.promo.window.years", "2"));
        int songWindowMonths  = Integer.parseInt(config.getProperty("youtube.song.window.months", "6"));
        double matchThreshold = Double.parseDouble(config.getProperty("youtube.match.threshold", "0.75"));
        int recheckDays       = Integer.parseInt(config.getProperty("youtube.recheck.interval.days", "30"));

        unitsUsedThisCycle = 0;
        YoutubeApiClient api = new YoutubeApiClient(apiKey);
        String today = LocalDate.now().toString();

        int moviesProcessed = 0, moviesMatched = 0, errors = 0;
        boolean quotaExceeded = false;
        long[] lastRequestAt = {0};

        try (YoutubeDatabaseService db = new YoutubeDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                log("Table '" + tableName + "' does not yet exist — skipping cycle.");
                return false;
            }
            db.ensureColumnsExist();

            List<YoutubeDatabaseService.Candidate> candidates = db.getCandidates(recheckDays, candidateBatch);
            log(String.format("Found %,d candidate movie(s) needing YouTube enrichment (post-2010, most recent first).",
                candidates.size()));

            for (YoutubeDatabaseService.Candidate c : candidates) {
                int neededSearches = (c.needsTrailer ? 1 : 0) + (c.needsTeaser ? 1 : 0) + (c.needsSong ? 1 : 0);
                long requiredUnits = (long) neededSearches * YoutubeApiClient.SEARCH_COST_UNITS
                    + YoutubeApiClient.VIDEOS_COST_UNITS;
                if (unitsUsedThisCycle + requiredUnits > dailyQuotaUnits) {
                    log(String.format("Daily quota budget (%,d units) reached after %,d movie(s) — stopping cycle.",
                        dailyQuotaUnits, moviesProcessed));
                    break;
                }

                LocalDate releaseDate = parseDate(c.releaseDate);
                if (releaseDate == null) {
                    logErr("Unparseable release_date '" + c.releaseDate + "' for '" + c.movieName + "' — skipping.");
                    continue;
                }

                try {
                    boolean matched = processCandidate(api, db, c, releaseDate, promoWindowYears, songWindowMonths,
                        matchThreshold, requestDelayMs, lastRequestAt, today);
                    moviesProcessed++;
                    if (matched) moviesMatched++;
                } catch (IOException e) {
                    if (isQuotaExceeded(e)) {
                        logErr("YouTube API quota exceeded — stopping cycle early. " + e.getMessage());
                        quotaExceeded = true;
                        break;
                    }
                    logErr("Failed to process '" + c.movieName + "' (" + c.year + "): " + e.getMessage());
                    db.rollback();
                    errors++;
                } catch (Exception e) {
                    logErr("Failed to process '" + c.movieName + "' (" + c.year + "): " + e.getMessage());
                    db.rollback();
                    errors++;
                }
            }
        }

        log(String.format("Cycle complete: %,d processed, %,d matched at least one video, %,d error(s), ~%,d quota units used.",
            moviesProcessed, moviesMatched, errors, unitsUsedThisCycle));
        return quotaExceeded;
    }

    /**
     * Runs enrichment for exactly one (movie_name, year), regardless of what's already
     * filled in or when it was last checked — for manual spot-checks of a real API key
     * against a real movie. Intended for the {@code --youtube-scan-movie} CLI mode.
     */
    public void runOnceForMovie(String movieName, String year) throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String apiKey = secrets.getProperty("youtube.api.key", "");
        if (apiKey.isBlank()) {
            logErr("youtube.api.key is not set in secrets.properties — aborting.");
            return;
        }

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        long requestDelayMs   = Long.parseLong(config.getProperty("youtube.request.delay.ms", "350"));
        int promoWindowYears  = Integer.parseInt(config.getProperty("youtube.promo.window.years", "2"));
        int songWindowMonths  = Integer.parseInt(config.getProperty("youtube.song.window.months", "6"));
        double matchThreshold = Double.parseDouble(config.getProperty("youtube.match.threshold", "0.75"));

        unitsUsedThisCycle = 0;
        YoutubeApiClient api = new YoutubeApiClient(apiKey);
        long[] lastRequestAt = {0};
        String today = LocalDate.now().toString();

        try (YoutubeDatabaseService db = new YoutubeDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            if (!db.tableExists()) {
                logErr("Table '" + tableName + "' does not exist.");
                return;
            }
            db.ensureColumnsExist();

            YoutubeDatabaseService.Candidate c = db.findMovieForTest(movieName, year);
            if (c == null) {
                logErr("No row found for movie_name='" + movieName + "', year='" + year + "'.");
                return;
            }
            LocalDate releaseDate = parseDate(c.releaseDate);
            if (releaseDate == null) {
                logErr("'" + c.movieName + "' (" + year + ") has no full-precision release_date " +
                    "(got '" + c.releaseDate + "') — cannot compute days-to-release.");
                return;
            }
            log("Testing '" + c.movieName + "', release_date=" + releaseDate + "...");
            try {
                processCandidate(api, db, c, releaseDate, promoWindowYears, songWindowMonths,
                    matchThreshold, requestDelayMs, lastRequestAt, today);
            } catch (IOException e) {
                db.rollback();
                if (isQuotaExceeded(e)) {
                    logErr("YouTube API daily quota exceeded — try again after the quota resets (~midnight Pacific).");
                    return;
                }
                logErr("Failed: " + e.getMessage());
                throw e;
            } catch (Exception e) {
                logErr("Failed: " + e.getMessage());
                db.rollback();
                throw e;
            }
        }
        log(String.format("Done. ~%,d quota unit(s) used.", unitsUsedThisCycle));
    }

    /** Searches, matches, fetches stats, and persists YouTube data for one candidate. */
    private boolean processCandidate(YoutubeApiClient api, YoutubeDatabaseService db,
                                      YoutubeDatabaseService.Candidate c, LocalDate releaseDate,
                                      int promoWindowYears, int songWindowMonths, double matchThreshold,
                                      long requestDelayMs, long[] lastRequestAt, String today) throws Exception {
        Map<String, YoutubeVideoMatch> matches = findVideos(
            api, c, releaseDate, promoWindowYears, songWindowMonths, matchThreshold, requestDelayMs, lastRequestAt);

        if (!matches.isEmpty()) {
            api.fillStatistics(new ArrayList<>(matches.values()));
        }

        YoutubeRecord record = buildRecord(matches, releaseDate);
        db.updateYoutubeData(c.movieName, c.year, record);
        db.markChecked(c.movieName, c.year, today);
        logMatchSummary(c, matches, record);
        return !matches.isEmpty();
    }

    private void logMatchSummary(YoutubeDatabaseService.Candidate c, Map<String, YoutubeVideoMatch> matches,
                                  YoutubeRecord record) {
        logCategory("trailer", c.needsTrailer, matches.get("trailer"), record.trailerDate,
            record.trailerDaysToRelease, record.trailerViews, record.trailerComments);
        logCategory("teaser", c.needsTeaser, matches.get("teaser"), record.teaserDate,
            record.teaserDaysToRelease, record.teaserViews, record.teaserComments);
        logCategory("song", c.needsSong, matches.get("song"), record.songDate,
            record.songDaysToRelease, record.songViews, record.songComments);
    }

    private void logCategory(String label, boolean wasAttempted, YoutubeVideoMatch m, LocalDate date,
                              Integer daysToRelease, Long views, Long comments) {
        if (!wasAttempted) {
            log("  " + label + ": not searched (already filled, or — for song — not an Indian-language release)");
        } else if (m == null) {
            log("  " + label + ": no confident match found");
        } else {
            log(String.format("  %-7s \"%s\" | published %s (%s days before release) | %s views | %s comments",
                label, m.title, date, daysToRelease, views, comments));
        }
    }

    /**
     * Category-relevant words a trailer/teaser title must contain to be accepted. A "teaser
     * trailer" title contains the literal word "trailer" (many teasers are titled that way), so
     * the trailer category must also exclude "teaser" — otherwise a teaser can outrank and get
     * claimed as the trailer, pushing the real trailer out via the used-video-id dedup and
     * leaving the teaser category to fall back to a worse match.
     */
    private static final String[] TRAILER_KEYWORDS = {"trailer"};
    private static final String[] TRAILER_EXCLUDE_KEYWORDS = {"teaser"};
    private static final String[] TEASER_KEYWORDS = {"teaser"};
    private static final String[] TEASER_EXCLUDE_KEYWORDS = {};
    /** A "song" match must NOT look like a trailer/teaser that slipped through the movie-name filter. */
    private static final String[] NOT_A_SONG_KEYWORDS = {"trailer", "teaser"};
    /** Generic clickbait/listicle patterns that occasionally slip past the movie-name filter. */
    private static final String[] SPAM_PATTERNS =
        {"top 10", "top 5", "#shorts", "compilation", "ranked", "ranking", "reaction"};

    /** Searches for whichever of trailer/teaser/song this candidate is still missing. */
    private Map<String, YoutubeVideoMatch> findVideos(YoutubeApiClient api, YoutubeDatabaseService.Candidate c,
                                                        LocalDate releaseDate, int promoWindowYears,
                                                        int songWindowMonths, double matchThreshold,
                                                        long requestDelayMs, long[] lastRequestAt)
            throws IOException, InterruptedException {
        LocalDate publishedAfter  = releaseDate.minusYears(promoWindowYears);
        LocalDate publishedBefore = releaseDate;

        Map<String, YoutubeVideoMatch> matches = new LinkedHashMap<>();
        // Tracks video ids already claimed by an earlier category this movie, so a weak/generic
        // query (e.g. "first single" against a movie with no promotional single) can't fall back
        // to re-matching the trailer or teaser video under a different category.
        java.util.Set<String> usedVideoIds = new java.util.HashSet<>();

        if (c.needsTrailer) {
            YoutubeVideoMatch m = bestMatch(api, c.movieName, "official trailer", TRAILER_KEYWORDS,
                TRAILER_EXCLUDE_KEYWORDS, publishedAfter, publishedBefore, matchThreshold,
                requestDelayMs, lastRequestAt, usedVideoIds);
            if (m != null) { matches.put("trailer", m); usedVideoIds.add(m.videoId); }
        }
        if (c.needsTeaser) {
            YoutubeVideoMatch m = bestMatch(api, c.movieName, "official teaser", TEASER_KEYWORDS,
                TEASER_EXCLUDE_KEYWORDS, publishedAfter, publishedBefore, matchThreshold,
                requestDelayMs, lastRequestAt, usedVideoIds);
            if (m != null) { matches.put("teaser", m); usedVideoIds.add(m.videoId); }
        }
        if (c.needsSong) {
            // Bollywood/regional song releases are usually titled "{Song Title} | {Movie} |
            // {Cast} | {Composers}" with no literal "song"/"single" word, so a keyword
            // requirement (like trailer/teaser use) produces false negatives. Instead: exclude
            // anything that's obviously a trailer/teaser or generic clickbait, restrict to a
            // tight pre-release window (songs release close to the movie, unlike teasers which
            // can be announced years out), prefer a video from the same channel as the trailer/
            // teaser (the strongest signal of "official" available from search results alone),
            // and pick the earliest-published survivor — approximating "first single".
            String officialChannelId = matches.containsKey("trailer") ? matches.get("trailer").channelId
                : matches.containsKey("teaser") ? matches.get("teaser").channelId : null;
            LocalDate songPublishedAfter = releaseDate.minusMonths(songWindowMonths);
            YoutubeVideoMatch m = earliestMatch(api, c.movieName, "first single", NOT_A_SONG_KEYWORDS,
                officialChannelId, songPublishedAfter, publishedBefore, matchThreshold,
                requestDelayMs, lastRequestAt, usedVideoIds);
            if (m != null) { matches.put("song", m); usedVideoIds.add(m.videoId); }
        }
        return matches;
    }

    /**
     * requiredKeywords guards against the query latching onto an unrelated video — a reaction,
     * ranking, or analysis video — just because its title happens to contain the movie's name.
     * excludeKeywords rejects a result even if it has a required keyword (e.g. a "teaser
     * trailer" title contains "trailer" but must not be accepted as the trailer). Returns the
     * first (most relevant) result satisfying both.
     */
    private YoutubeVideoMatch bestMatch(YoutubeApiClient api, String movieName, String querySuffix,
                                         String[] requiredKeywords, String[] excludeKeywords,
                                         LocalDate publishedAfter, LocalDate publishedBefore,
                                         double matchThreshold, long requestDelayMs, long[] lastRequestAt,
                                         java.util.Set<String> excludeVideoIds)
            throws IOException, InterruptedException {
        for (YoutubeVideoMatch m : search(api, movieName, querySuffix, publishedAfter, publishedBefore,
                requestDelayMs, lastRequestAt, matchThreshold, excludeVideoIds)) {
            if (containsAnyKeyword(m.title, excludeKeywords)) continue;
            if (containsAnyKeyword(m.title, requiredKeywords)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Like bestMatch, but rejects results matching excludeKeywords or SPAM_PATTERNS (rather than
     * requiring a keyword) and returns the earliest-published survivor instead of the most
     * relevant one. When preferredChannelId is non-null (the channel that uploaded the trailer
     * or teaser), a survivor from that same channel is preferred over an earlier-published one
     * from an unknown channel — official studio/label channels post trailer, teaser, and song
     * videos from the same channel, which is a far stronger "this is legitimate" signal than
     * title text alone.
     */
    private YoutubeVideoMatch earliestMatch(YoutubeApiClient api, String movieName, String querySuffix,
                                             String[] excludeKeywords, String preferredChannelId,
                                             LocalDate publishedAfter, LocalDate publishedBefore,
                                             double matchThreshold, long requestDelayMs,
                                             long[] lastRequestAt, java.util.Set<String> excludeVideoIds)
            throws IOException, InterruptedException {
        List<YoutubeVideoMatch> candidates = new ArrayList<>();
        for (YoutubeVideoMatch m : search(api, movieName, querySuffix, publishedAfter, publishedBefore,
                requestDelayMs, lastRequestAt, matchThreshold, excludeVideoIds)) {
            if (containsAnyKeyword(m.title, excludeKeywords)) continue;
            if (containsAnyKeyword(m.title, SPAM_PATTERNS)) continue;
            candidates.add(m);
        }
        if (preferredChannelId != null) {
            YoutubeVideoMatch onChannel = earliestOf(candidates, preferredChannelId);
            if (onChannel != null) return onChannel;
        }
        return earliestOf(candidates, null);
    }

    /** Earliest-published match in the list; if channelId is non-null, only considers that channel. */
    private YoutubeVideoMatch earliestOf(List<YoutubeVideoMatch> candidates, String channelId) {
        YoutubeVideoMatch earliest = null;
        for (YoutubeVideoMatch m : candidates) {
            if (channelId != null && !channelId.equals(m.channelId)) continue;
            if (earliest == null || m.publishedAt.isBefore(earliest.publishedAt)) {
                earliest = m;
            }
        }
        return earliest;
    }

    /** Runs the search, filters to plausible candidates (published, unused, name matches). */
    private List<YoutubeVideoMatch> search(YoutubeApiClient api, String movieName, String querySuffix,
                                            LocalDate publishedAfter, LocalDate publishedBefore,
                                            long requestDelayMs, long[] lastRequestAt, double matchThreshold,
                                            java.util.Set<String> excludeVideoIds)
            throws IOException, InterruptedException {
        throttle(lastRequestAt, requestDelayMs);
        List<YoutubeVideoMatch> results = api.search(movieName + " " + querySuffix,
            publishedAfter, publishedBefore, SEARCH_RESULTS_PER_QUERY);
        unitsUsedThisCycle += YoutubeApiClient.SEARCH_COST_UNITS;
        List<YoutubeVideoMatch> candidates = new ArrayList<>();
        for (YoutubeVideoMatch m : results) {
            if (m.publishedAt == null) continue;
            if (excludeVideoIds.contains(m.videoId)) continue;
            if (titleMatchScore(movieName, m.title) < matchThreshold) continue;
            candidates.add(m);
        }
        return candidates;
    }

    private YoutubeRecord buildRecord(Map<String, YoutubeVideoMatch> matches, LocalDate releaseDate) {
        YoutubeRecord record = new YoutubeRecord();

        YoutubeVideoMatch trailer = matches.get("trailer");
        if (trailer != null && trailer.publishedAt != null) {
            record.trailerDate = trailer.publishedAt.toLocalDate();
            record.trailerDaysToRelease = (int) ChronoUnit.DAYS.between(record.trailerDate, releaseDate);
            record.trailerViews = trailer.viewCount;
            record.trailerComments = trailer.commentCount;
        }
        YoutubeVideoMatch teaser = matches.get("teaser");
        if (teaser != null && teaser.publishedAt != null) {
            record.teaserDate = teaser.publishedAt.toLocalDate();
            record.teaserDaysToRelease = (int) ChronoUnit.DAYS.between(record.teaserDate, releaseDate);
            record.teaserViews = teaser.viewCount;
            record.teaserComments = teaser.commentCount;
        }
        YoutubeVideoMatch song = matches.get("song");
        if (song != null && song.publishedAt != null) {
            record.songDate = song.publishedAt.toLocalDate();
            record.songDaysToRelease = (int) ChronoUnit.DAYS.between(record.songDate, releaseDate);
            record.songViews = song.viewCount;
            record.songComments = song.commentCount;
        }
        return record;
    }

    /**
     * Google's actual quota-exceeded response (confirmed against the live API) is HTTP 429
     * with reason "rateLimitExceeded" / status "RESOURCE_EXHAUSTED" — not HTTP 403 or the
     * string "quotaExceeded" as might be assumed from older API documentation. Matching the
     * wrong signature here means the cycle wouldn't stop early: it would instead re-attempt
     * (and re-fail) against every remaining candidate for no benefit.
     */
    private boolean isQuotaExceeded(IOException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("HTTP 429") || msg.contains("RESOURCE_EXHAUSTED")
            || msg.contains("rateLimitExceeded") || msg.contains("quotaExceeded"));
    }

    private LocalDate parseDate(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 10) return null;
        try {
            return LocalDate.parse(releaseDate.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- title matching ----

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
     * Fraction of the movie name's tokens that appear as whole tokens in the video title,
     * in [0, 1]. Video titles carry extra decoration ("Official Trailer | Actor Name"),
     * so this checks containment rather than whole-string similarity.
     */
    static double titleMatchScore(String movieName, String videoTitle) {
        String[] nameTokens = normalize(movieName).split(" ");
        java.util.Set<String> titleTokens = new java.util.HashSet<>(
            java.util.Arrays.asList(normalize(videoTitle).split(" ")));
        int total = 0, matched = 0;
        for (String token : nameTokens) {
            if (token.isBlank()) continue;
            total++;
            if (titleTokens.contains(token)) matched++;
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }

    /** True if the video title contains at least one of the given keywords (case-insensitive). */
    static boolean containsAnyKeyword(String videoTitle, String[] keywords) {
        String normalized = normalize(videoTitle);
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) return true;
        }
        return false;
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

    /** Sleeps just enough to keep at least delayMs between requests; updates lastRequestAt[0] in place. */
    private void throttle(long[] lastRequestAt, long delayMs) throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAt[0];
        if (lastRequestAt[0] > 0 && elapsed < delayMs) {
            Thread.sleep(delayMs - elapsed);
        }
        lastRequestAt[0] = System.currentTimeMillis();
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
