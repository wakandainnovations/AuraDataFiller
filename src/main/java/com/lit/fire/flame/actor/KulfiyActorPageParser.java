package com.lit.fire.flame.actor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses in.kulfiy.com actor movie-list pages
 * (e.g. https://in.kulfiy.com/movies/mohanlal-movies/).
 *
 * Page URLs are constructed directly from the actor name — kulfiy uses a
 * predictable "/movies/{actor-slug}-movies/" convention.
 *
 * These pages are WordPress blog posts containing a single plain-text table
 * (Year / Movies / Role / [Actress|Genre|Language...]) — no per-movie links,
 * so no source movie slug/id is available. Language/genre columns are present
 * only on some actor pages; when absent the caller falls back to an LLM lookup.
 */
public class KulfiyActorPageParser {

    private static final String BASE_URL = "https://in.kulfiy.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "<table[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TR_PATTERN = Pattern.compile(
        "<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TD_PATTERN = Pattern.compile(
        "<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern YEAR_PAT = Pattern.compile("\\b((?:19[89]\\d|20[0-3]\\d))\\b");

    private final HttpClient httpClient;

    public KulfiyActorPageParser() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Constructs the expected kulfiy movie-list URL for a given actor name.
     * "Mohanlal" → https://in.kulfiy.com/movies/mohanlal-movies/
     */
    public String constructMoviesUrl(String actorName) {
        return BASE_URL + "/movies/" + slug(actorName) + "-movies/";
    }

    private String slug(String actorName) {
        return actorName.trim().toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    /** Fetches and parses kulfiy's robots.txt (Crawl-delay + Disallow for User-agent: *). */
    public RobotsTxtPolicy fetchRobotsPolicy() {
        try {
            String body = fetch(BASE_URL + "/robots.txt", BASE_URL + "/");
            return RobotsTxtPolicy.parse(body, 1000L);
        } catch (Exception e) {
            return RobotsTxtPolicy.parse("", 1000L);
        }
    }

    /**
     * Fetches and parses the actor's movie-list page. Returns an empty list if the
     * page doesn't exist (404) or no parseable table is found.
     */
    public List<ActorMovieEntry> parseActorMovies(String actorName, String url)
            throws IOException, InterruptedException {
        String html = fetch(url, BASE_URL + "/");
        if (html.length() < 500) return List.of();

        List<ActorMovieEntry> entries = parseFromTable(actorName, html);

        // Keep only post-1980 (and remove entries with completely unknown dates)
        entries.removeIf(e -> {
            if (e.releaseDate() == null) return true;
            String y = e.releaseDate().substring(0, Math.min(4, e.releaseDate().length()));
            try { return Integer.parseInt(y) <= 1980; }
            catch (NumberFormatException ex) { return true; }
        });
        return entries;
    }

    private List<ActorMovieEntry> parseFromTable(String actorName, String html) {
        List<ActorMovieEntry> result = new ArrayList<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(html);

        while (tableMatcher.find()) {
            String tbl = tableMatcher.group(1);
            Matcher rowMatcher = TR_PATTERN.matcher(tbl);

            List<String> headers = null;
            int yearIdx = -1, movieIdx = -1, roleIdx = -1, langIdx = -1, genreIdx = -1;

            while (rowMatcher.find()) {
                List<String> cells = new ArrayList<>();
                Matcher cellM = TD_PATTERN.matcher(rowMatcher.group(1));
                while (cellM.find()) cells.add(stripTags(cellM.group(1)));
                if (cells.isEmpty()) continue;

                if (headers == null) {
                    // First row is the header (plain text cells, e.g. Year/Movies/Role/Actress)
                    headers = cells;
                    yearIdx  = colOf(headers, "year", "released", "date");
                    movieIdx = colOf(headers, "movie", "film", "title");
                    roleIdx  = colOf(headers, "role", "character", "as");
                    langIdx  = colOf(headers, "language", "lang");
                    genreIdx = colOf(headers, "genre");
                    if (movieIdx < 0 || yearIdx < 0) break; // not the movies table — skip
                    continue;
                }

                String movieName = movieIdx < cells.size() ? blankToNull(cells.get(movieIdx)) : null;
                if (movieName == null || movieName.matches("\\d+")) continue;

                String releaseDate = yearIdx >= 0 && yearIdx < cells.size()
                    ? extractYear(cells.get(yearIdx)) : null;
                if (releaseDate == null) {
                    for (String c : cells) { releaseDate = extractYear(c); if (releaseDate != null) break; }
                }

                String role     = roleIdx  >= 0 && roleIdx  < cells.size() ? blankToNull(cells.get(roleIdx))  : null;
                String language = langIdx  >= 0 && langIdx  < cells.size() ? blankToNull(cells.get(langIdx))  : null;
                String genre    = genreIdx >= 0 && genreIdx < cells.size() ? blankToNull(cells.get(genreIdx)) : null;

                result.add(new ActorMovieEntry(
                    actorName, movieName, releaseDate, language, genre, null, role, null));
            }

            if (!result.isEmpty()) return result; // use first table with parseable movie data
        }
        return result;
    }

    private int colOf(List<String> headers, String... keywords) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).toLowerCase();
            for (String kw : keywords) if (h.contains(kw)) return i;
        }
        return -1;
    }

    private String extractYear(String text) {
        if (text == null) return null;
        Matcher m = YEAR_PAT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        return HTML_TAG.matcher(html).replaceAll("")
            .replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&#8217;", "'").replace("&#8216;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || "-".equals(t) || "na".equalsIgnoreCase(t) || "n/a".equalsIgnoreCase(t)) ? null : t;
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
