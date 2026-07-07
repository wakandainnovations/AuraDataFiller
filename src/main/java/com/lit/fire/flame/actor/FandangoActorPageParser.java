package com.lit.fire.flame.actor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses fandango.com actor film-credits pages
 * (e.g. https://www.fandango.com/people/ajith-kumar-6861/film-credits).
 *
 * Unlike sacnilk/kulfiy, fandango person URLs embed an opaque internal id
 * ("ajith-kumar-6861") that can't be derived from the actor name alone, so the
 * actor's person page is first resolved via fandango's search endpoint
 * (/search?q=Name), fuzzy-matching the returned display names to the DB actor name.
 */
public class FandangoActorPageParser {

    private static final String BASE_URL = "https://www.fandango.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern PERSON_LINK = Pattern.compile(
        "href=\"(/people/[a-z0-9-]+-\\d+)\"[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TR_PATTERN = Pattern.compile(
        "<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_CELL = Pattern.compile(
        "class=\"pop-tabular--row__year\"[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_CELL = Pattern.compile(
        "class=\"pop-tabular--row__film-title\"[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_CELL = Pattern.compile(
        "class=\"pop-tabular--row__role\"[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN = Pattern.compile(
        "href=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVIE_SLUG_PAT = Pattern.compile("^/([a-z0-9-]+)/movie-overview");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final HttpClient httpClient;

    public FandangoActorPageParser() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /** Fetches and parses fandango's robots.txt (Crawl-delay + Disallow for User-agent: *). */
    public RobotsTxtPolicy fetchRobotsPolicy() {
        try {
            String body = fetch(BASE_URL + "/robots.txt", BASE_URL + "/");
            return RobotsTxtPolicy.parse(body, 1000L);
        } catch (Exception e) {
            return RobotsTxtPolicy.parse("", 1000L);
        }
    }

    /**
     * Searches fandango for the actor's person page and returns the best-matching
     * film-credits URL, or null if no candidate scores above {@code threshold}.
     */
    public String findFilmCreditsUrl(String actorName, double threshold)
            throws IOException, InterruptedException {
        String searchUrl = BASE_URL + "/search?q=" + URLEncoder.encode(actorName, StandardCharsets.UTF_8);
        String html = fetch(searchUrl, BASE_URL + "/");
        if (html.isEmpty()) return null;

        // href → best display text seen for that href (skip empty-text image-wrapper anchors)
        Map<String, String> candidates = new LinkedHashMap<>();
        Matcher m = PERSON_LINK.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String text = blankToNull(stripTags(m.group(2)));
            if (text != null) candidates.putIfAbsent(href, text);
        }

        String bestHref = null;
        double bestScore = 0;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            double score = similarity(normalize(actorName), normalize(e.getValue()));
            if (score > bestScore) { bestScore = score; bestHref = e.getKey(); }
        }

        if (bestHref == null || bestScore < threshold) return null;
        return BASE_URL + bestHref + "/film-credits";
    }

    /**
     * Fetches and parses the actor's film-credits page. Returns an empty list if
     * the page doesn't exist (404) or no filmography table is found.
     */
    public List<ActorMovieEntry> parseFilmCredits(String actorName, String url)
            throws IOException, InterruptedException {
        String html = fetch(url, BASE_URL + "/");
        if (html.length() < 500) return List.of();

        List<ActorMovieEntry> entries = new ArrayList<>();
        Matcher rowMatcher = TR_PATTERN.matcher(html);
        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);

            Matcher titleM = TITLE_CELL.matcher(row);
            if (!titleM.find()) continue;
            String titleCell = titleM.group(1);

            String movieName = blankToNull(stripTags(titleCell));
            if (movieName == null || movieName.equalsIgnoreCase("Title")) continue; // header row
            // fandango appends the release year to the title itself for some entries,
            // e.g. "Vidaamuyarchi (2025)" — strip it so movie_name matches other sources.
            movieName = movieName.replaceAll("\\s*\\(((?:19[89]\\d|20[0-3]\\d))\\)\\s*$", "").trim();

            String movieSlug = null;
            Matcher hrefM = HREF_PATTERN.matcher(titleCell);
            if (hrefM.find()) {
                Matcher slugM = MOVIE_SLUG_PAT.matcher(hrefM.group(1));
                if (slugM.find()) movieSlug = slugM.group(1);
            }

            Matcher yearM = YEAR_CELL.matcher(row);
            String releaseDate = yearM.find() ? extractYear(stripTags(yearM.group(1))) : null;

            Matcher roleM = ROLE_CELL.matcher(row);
            String role = roleM.find() ? blankToNull(stripTags(roleM.group(1))) : null;

            entries.add(new ActorMovieEntry(
                actorName, movieName, releaseDate, null, null, null, role, movieSlug));
        }

        // Keep only post-1980 (and remove entries with completely unknown dates)
        entries.removeIf(e -> {
            if (e.releaseDate() == null) return true;
            String y = e.releaseDate().substring(0, Math.min(4, e.releaseDate().length()));
            try { return Integer.parseInt(y) <= 1980; }
            catch (NumberFormatException ex) { return true; }
        });
        return entries;
    }

    // ---- helpers ----

    private static String extractYear(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\b(19[89]\\d|20[0-3]\\d)\\b").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        return HTML_TAG.matcher(html).replaceAll("")
            .replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static String normalize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Levenshtein-ratio similarity between two normalised name strings. */
    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        int minLen = Math.min(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        if ((double) minLen / maxLen < 0.4) return 0.0;
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

    String fetch(String url, String referer) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .version(HttpClient.Version.HTTP_1_1)
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         referer)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return "";
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        return resp.body();
    }
}
