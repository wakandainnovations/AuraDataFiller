package com.lit.fire.flame.moviedetails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lit.fire.flame.mapper.ColumnMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Client for the real TMDB (The Movie Database) API — replaces the earlier AuraLLM-guessing
 * approach for this same set of fields with an actual structured, continuously-maintained movie
 * database. Unlike AuraLLM, TMDB has no "knowledge cutoff": it's looked up live, so every
 * candidate is in scope regardless of release year.
 *
 * Two auth schemes are supported (TMDB currently documents both): the modern v4 "API Read
 * Access Token" (a long token, sent as an Authorization: Bearer header — preferred when
 * present) and the classic v3 "API Key" (a short key, sent as an api_key query parameter).
 * Configured via secrets.properties: tmdb.api.read.access.token and/or tmdb.api.key.
 *
 * TMDB documents a ~40 requests/second limit. Every HTTP call this client makes funnels through
 * {@link #get}, which enforces a minimum inter-request gap (see {@link #throttle}) computed from
 * the configured requests/second — not just once per candidate movie, since a single
 * {@link #lookup} can fan out into several requests (one search per title alias per matching
 * pass, plus a details call on a match) that would otherwise burst well past the limit.
 *
 * A search can return several same-titled movies (different years, remakes, different
 * industries entirely — e.g. one-word titles like "12" or "10" are extremely common across
 * languages). {@link #lookup} only ever accepts a candidate result when its release year matches
 * (or, on a relaxed second pass, is within one year of) the DB row's year AND its title is a
 * close fuzzy match AND — when the DB row's language is known — its original_language maps to
 * that same language, unless the title match is near-exact. Failing to find any result meeting
 * that bar returns null rather than guessing, exactly like AuraLLM was told to return null for
 * low confidence — except here the uncertainty is "which TMDB record is this", not "what are the
 * facts", so once matched, every field returned is real TMDB data.
 */
public class TmdbClient {

    private static final String API_BASE = "https://api.themoviedb.org/3";

    /** A same-titled match within 1 year needs a substantially closer title match than an exact-year one. */
    private static final double RELAXED_YEAR_THRESHOLD_BONUS = 0.10;

    /** A title match this close is trusted even when the language on file doesn't line up (the DB's own language may be wrong/blank) — only for a reasonably distinctive title, see the "distinctiveTitle" check in {@link #lookup}. */
    private static final double LANGUAGE_OVERRIDE_SIMILARITY = 0.95;

    /**
     * Minimum required gap between the best and second-best (distinct-movie) match scores. Two
     * unrelated films can legitimately share an exact, short title (confirmed in practice: an
     * Italian film and an unrelated Hong Kong film both titled "12", released the same year) —
     * when nothing separates them, guessing which one this DB row actually is would be exactly
     * the kind of confident-sounding wrong answer this service exists to avoid.
     */
    private static final double AMBIGUOUS_MATCH_GAP = 0.05;

    /** Lowercase/slash cbfc_rating variants TMDB's India certification field may carry -> canonical certificate. */
    private static final Map<String, String> CANONICAL_CBFC_RATINGS = Map.of(
        "u",   "U",
        "ua",  "UA",
        "u/a", "UA",
        "a",   "A"
    );

    private final String bearerToken;
    private final String apiKeyV3;
    private final double matchThreshold;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ColumnMapper columnMapper = new ColumnMapper();

    /**
     * Minimum gap (ms) enforced before every single HTTP call this client makes — see
     * {@link #throttle()}. TMDB's documented limit is ~40 requests/second; a single
     * {@link #lookup} can issue several requests (one search per title alias per matching
     * pass, plus one details call on a match), so the limit has to be enforced per-request here
     * rather than once per candidate movie by the caller, or a multi-alias title could burst
     * well past 40 req/s even with a per-candidate delay in place.
     */
    private final long minRequestIntervalMs;
    private long lastRequestAtMs = 0;

    public TmdbClient(String bearerToken, String apiKeyV3, int timeoutSeconds, double matchThreshold,
                       double maxRequestsPerSecond) {
        this.bearerToken    = (bearerToken == null || bearerToken.isBlank()) ? null : bearerToken.trim();
        this.apiKeyV3       = (apiKeyV3 == null || apiKeyV3.isBlank()) ? null : apiKeyV3.trim();
        this.matchThreshold = matchThreshold;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.minRequestIntervalMs = Math.round(1000.0 / maxRequestsPerSecond);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public boolean isConfigured() {
        return bearerToken != null || apiKeyV3 != null;
    }

    /** One (title, year, TMDB movie id, original_language) candidate from a TMDB search result. */
    private record SearchHit(int id, String title, String originalTitle, String originalLanguage, Integer year) {}

    /**
     * Searches TMDB for {@code movieName} (which may carry several comma-separated title
     * aliases, e.g. "Baahubali 2: The Conclusion, Bãhubali 2: The Conclusion" — every alias is
     * tried), picks the single best-matching movie for {@code year}/{@code languagesOnFile}, and
     * returns its full details — or null when nothing meets the confidence bar (see class
     * javadoc), in which case the caller should leave the row untouched rather than guess.
     *
     * @param languagesOnFile every distinct language already on file for this movie/year
     *                        (comma-separated, e.g. "hindi, tamil"), or null/blank if unknown
     */
    public MovieDetails lookup(String movieName, String year, String languagesOnFile) throws Exception {
        Integer targetYear = parseYear(year);
        if (targetYear == null) return null;

        Set<String> knownLanguages = languagesOnFile == null || languagesOnFile.isBlank()
            ? Set.of()
            : java.util.Arrays.stream(languagesOnFile.split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        List<String> aliases = java.util.Arrays.stream(movieName.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (aliases.isEmpty()) return null;

        SearchHit best = null;
        double bestScore = -1;
        double secondBestScore = -1;

        // Pass 1: exact-year match at the normal threshold. Pass 2 (only if pass 1 found
        // nothing): +/-1 year at a stricter threshold, for the common case of a title's
        // "release year" differing by one between sources (year-end/new-year releases, or a
        // festival premiere vs. wide release).
        for (int pass = 0; pass < 2; pass++) {
            boolean allowAdjacentYear = pass == 1;
            for (String alias : aliases) {
                String normalizedAlias = normalize(alias);
                // A short/generic title (e.g. "12", "10", "1") trivially string-matches ANY
                // same-titled film worldwide, so an exact match alone must not be allowed to
                // bypass the language check below — confirmed in practice: an Italian film
                // titled "12" matched a completely unrelated Hong Kong film also titled "12"
                // because the title-similarity override treated the exact match as decisive.
                boolean distinctiveTitle = normalizedAlias.length() >= 8;

                for (SearchHit hit : search(alias, targetYear, allowAdjacentYear)) {
                    if (hit.year() == null) continue;
                    int yearDiff = Math.abs(hit.year() - targetYear);
                    if (allowAdjacentYear ? yearDiff > 1 : yearDiff != 0) continue;

                    double score = Math.max(
                        similarity(normalizedAlias, normalize(hit.title())),
                        similarity(normalizedAlias, normalize(hit.originalTitle())));

                    double effectiveThreshold = matchThreshold + (allowAdjacentYear ? RELAXED_YEAR_THRESHOLD_BONUS : 0);
                    if (score < effectiveThreshold) continue;

                    if (!knownLanguages.isEmpty() && (!distinctiveTitle || score < LANGUAGE_OVERRIDE_SIMILARITY)) {
                        String mappedLanguage = columnMapper.expandLanguageCode(hit.originalLanguage()).toLowerCase();
                        if (!knownLanguages.contains(mappedLanguage)) continue;
                    }

                    if (best != null && hit.id() == best.id()) continue;
                    if (score > bestScore) {
                        secondBestScore = bestScore;
                        bestScore = score;
                        best = hit;
                    } else if (score > secondBestScore) {
                        secondBestScore = score;
                    }
                }
            }
            if (best != null) break;
        }

        if (best == null) return null;
        // Two distinct movies scored almost identically (a common-titled movie with no other
        // disambiguating signal available) — refuse to guess which one this DB row actually is,
        // same principle as every other "not confident enough" rejection in this class.
        if (secondBestScore >= 0 && bestScore - secondBestScore < AMBIGUOUS_MATCH_GAP) return null;

        return fetchDetails(best.id());
    }

    /**
     * Returns every TMDB movie ID whose origin country includes India and whose primary release
     * date falls within {@code year} (Jan 1 - Dec 31), via TMDB's /discover/movie endpoint
     * (with_origin_country=IN) — paginated until TMDB reports no further pages. Used by the
     * india_movies_data_collection catalog import (see
     * com.lit.fire.flame.indiamovies.IndiaMoviesImportService) to enumerate candidates before
     * fetching each one's full details (budget/revenue aren't filterable via discover itself, so
     * every candidate here still needs a details call — see {@link #fetchIndiaMovieRecord}).
     */
    public List<Integer> discoverIndianMovieIds(int year) throws Exception {
        List<Integer> ids = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        while (page <= totalPages && page <= 500) {
            String url = API_BASE + "/discover/movie" +
                "?with_origin_country=IN" +
                "&primary_release_date.gte=" + year + "-01-01" +
                "&primary_release_date.lte=" + year + "-12-31" +
                "&sort_by=primary_release_date.asc" +
                "&page=" + page;
            JsonNode root = get(url);
            for (JsonNode r : root.path("results")) {
                if (r.hasNonNull("id")) ids.add(r.path("id").asInt());
            }
            totalPages = root.path("total_pages").asInt(1);
            page++;
        }
        return ids;
    }

    /**
     * Fetches full TMDB details for {@code tmdbId} and returns a complete
     * {@link IndiaMovieRecord} — including its primary-key fields (title, release date, original
     * language) and budget/revenue, unlike {@link #lookup}'s {@link MovieDetails} — but only when
     * TMDB actually has both a non-zero budget AND a non-zero revenue on file; returns null
     * otherwise (this import is scoped to movies with real budget/revenue data, per its own
     * design — see IndiaMoviesImportService).
     */
    public IndiaMovieRecord fetchIndiaMovieRecord(int tmdbId) throws Exception {
        JsonNode root = get(API_BASE + "/movie/" + tmdbId + "?append_to_response=credits,release_dates");

        long budget = root.path("budget").asLong(0);
        long revenue = root.path("revenue").asLong(0);
        if (budget <= 0 || revenue <= 0) return null;

        String title = textOrNull(root.path("title"));
        String releaseDate = textOrNull(root.path("release_date"));
        if (title == null || releaseDate == null || parseYear(releaseDate) == null) return null;
        String language = columnMapper.expandLanguageCode(textOrNull(root.path("original_language")));

        Double rating = null;
        if (root.path("vote_count").asInt(0) > 0 && root.hasNonNull("vote_average")) {
            double v = root.path("vote_average").asDouble();
            if (v > 0) rating = Math.round(v * 10.0) / 10.0;
        }

        Integer runtime = null;
        if (root.hasNonNull("runtime")) {
            int r = root.path("runtime").asInt();
            if (r > 0) runtime = r;
        }

        return new IndiaMovieRecord(title, releaseDate, language, rating, runtime,
            joinNames(root.path("genres")), joinNames(root.path("production_countries")),
            budget, revenue, joinNames(root.path("production_companies")),
            directorsFrom(root.path("credits").path("crew")),
            cbfcRatingFrom(root.path("release_dates").path("results")));
    }

    // ---- TMDB HTTP calls ----

    private List<SearchHit> search(String query, int year, boolean adjacentYear) throws Exception {
        StringBuilder url = new StringBuilder(API_BASE)
            .append("/search/movie?query=").append(urlEncode(query))
            .append("&include_adult=false&page=1");
        if (!adjacentYear) {
            url.append("&primary_release_year=").append(year);
        }
        JsonNode root = get(url.toString());
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            Integer resultYear = parseYear(textOrNull(r.path("release_date")));
            hits.add(new SearchHit(
                r.path("id").asInt(),
                textOrNull(r.path("title")),
                textOrNull(r.path("original_title")),
                textOrNull(r.path("original_language")),
                resultYear));
        }
        return hits;
    }

    private MovieDetails fetchDetails(int tmdbId) throws Exception {
        JsonNode root = get(API_BASE + "/movie/" + tmdbId + "?append_to_response=credits,release_dates");

        Double rating = null;
        if (root.path("vote_count").asInt(0) > 0 && root.hasNonNull("vote_average")) {
            double v = root.path("vote_average").asDouble();
            if (v > 0) rating = Math.round(v * 10.0) / 10.0;
        }

        Integer runtime = null;
        if (root.hasNonNull("runtime")) {
            int r = root.path("runtime").asInt();
            if (r > 0) runtime = r;
        }

        String genre = joinNames(root.path("genres"));
        String country = joinNames(root.path("production_countries"));
        String productionCompanies = joinNames(root.path("production_companies"));
        String directors = directorsFrom(root.path("credits").path("crew"));
        String cbfcRating = cbfcRatingFrom(root.path("release_dates").path("results"));

        MovieDetails details = new MovieDetails(rating, runtime, genre, country, productionCompanies, directors, cbfcRating);
        return details.isEmpty() ? null : details;
    }

    private static String joinNames(JsonNode arrayNode) {
        List<String> names = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            String name = textOrNull(n.path("name"));
            if (name != null) names.add(name);
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static String directorsFrom(JsonNode crewArray) {
        List<String> directors = new ArrayList<>();
        for (JsonNode member : crewArray) {
            if ("Director".equals(textOrNull(member.path("job")))) {
                String name = textOrNull(member.path("name"));
                if (name != null) directors.add(name);
            }
        }
        return directors.isEmpty() ? null : String.join(", ", directors);
    }

    /** India (IN) certification from release_dates.results — TMDB's real CBFC-equivalent field. */
    private static String cbfcRatingFrom(JsonNode releaseDateResults) {
        for (JsonNode country : releaseDateResults) {
            if (!"IN".equals(textOrNull(country.path("iso_3166_1")))) continue;
            for (JsonNode rd : country.path("release_dates")) {
                String cert = textOrNull(rd.path("certification"));
                if (cert == null) continue;
                String normalized = CANONICAL_CBFC_RATINGS.get(cert.trim().toLowerCase().replace(" ", ""));
                if (normalized != null) return normalized;
            }
        }
        return null;
    }

    /** Retries on HTTP 429 (rate limit) up to this many additional times before giving up. */
    private static final int MAX_429_RETRIES = 5;

    private JsonNode get(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(requestTimeout)
                .header("Accept", "application/json");
            if (bearerToken != null) {
                builder.uri(URI.create(url)).header("Authorization", "Bearer " + bearerToken);
            } else {
                String sep = url.contains("?") ? "&" : "?";
                builder.uri(URI.create(url + sep + "api_key=" + apiKeyV3));
            }
            HttpRequest request = builder.GET().build();

            HttpResponse<String> response;
            try {
                response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(requestTimeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new Exception("TMDB request timed out after " + requestTimeout.toSeconds() + "s");
            }

            if (response.statusCode() == 429 && attempt < MAX_429_RETRIES) {
                // TMDB's ~40 req/s cap can still be brushed under bursty concurrent load even
                // with per-request throttling (many threads each individually under the cap can
                // still collectively exceed it at the instant TMDB samples). Honor Retry-After
                // when present, else back off by (attempt+1) throttle-intervals.
                long waitMs = response.headers().firstValueAsLong("Retry-After").isPresent()
                    ? response.headers().firstValueAsLong("Retry-After").getAsLong() * 1000
                    : minRequestIntervalMs * (attempt + 2);
                Thread.sleep(waitMs);
                continue;
            }
            if (response.statusCode() != 200) {
                throw new Exception("TMDB HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            return objectMapper.readTree(response.body());
        }
    }

    // ---- name normalisation and similarity (mirrors CreditsCrawlerService/RuntimeBudgetCrawlerService) ----

    static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        int minLen = Math.min(a.length(), b.length());
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

    private static Integer parseYear(String value) {
        if (value == null || value.length() < 4) return null;
        try {
            return Integer.parseInt(value.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    /** Blocks until at least {@link #minRequestIntervalMs} has elapsed since the previous HTTP call this client made. */
    private synchronized void throttle() throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAtMs;
        if (lastRequestAtMs > 0 && elapsed < minRequestIntervalMs) {
            Thread.sleep(minRequestIntervalMs - elapsed);
        }
        lastRequestAtMs = System.currentTimeMillis();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
