package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.*;

/**
 * Fetches and parses HTML from boxofficemojo.com.
 *
 * Crawl policy (boxofficemojo.com/robots.txt):
 *   - /search/ and /title/ and /releasegroup/ are not disallowed for the wildcard agent.
 *   - No Crawl-delay is specified; 2 000 ms is used between requests.
 *   - imdb.com IS excluded (robots.txt disallows all non-whitelisted bots for /search/).
 *
 * Strategy:
 *   1. Search for "{movieName} {year}" at /search/?q=...
 *   2. Find the result whose title and year best match the DB entry.
 *   3. Fetch the matched /releasegroup/ or /title/ page.
 *   4. Extract worldwide gross and production budget in USD (no conversion needed).
 */
public class BoxOfficeMojoParser {

    private static final String BASE_URL   = "https://www.boxofficemojo.com";
    private static final String SEARCH_URL = BASE_URL + "/search/?q=";
    static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Search results: href="/releasegroup/grXXX/" or href="/title/ttXXX/" (with optional sub-paths)
    private static final Pattern SEARCH_LINK = Pattern.compile(
        "href=\"(/(?:releasegroup/gr|title/tt)[^\"?#]*)\"[^>]*>([^<]+)</a>"
    );

    // Year in title text: "Inception (2010)"
    private static final Pattern YEAR_IN_TEXT = Pattern.compile("\\((\\d{4})\\)");

    // Worldwide gross on the title/release-group summary page.
    // BOM renders the summary as: ... Worldwide ... $1,234,567,890 ...
    // We use a lazy match so we grab the first $ after the "Worldwide" label.
    private static final Pattern WW_PATTERN = Pattern.compile(
        "Worldwide[\\s\\S]{0,2000}?\\$([\\d,]+)",
        Pattern.DOTALL
    );

    // Production budget on the same page.
    private static final Pattern BUDGET_PATTERN = Pattern.compile(
        "Production Budget[\\s\\S]{0,800}?\\$([\\d,]+)",
        Pattern.DOTALL
    );

    private final HttpClient httpClient;

    public BoxOfficeMojoParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Searches BOM for the given movie, fetches the best-matching page, and
     * returns a BoxOfficeRecord with pre-converted USD values (revenueUsd / budgetUsd).
     * Returns null when no sufficiently-similar result is found or the page has no data.
     */
    public BoxOfficeRecord searchAndParse(String movieName, String year, double matchThreshold)
            throws IOException, InterruptedException {
        String query    = URLEncoder.encode(movieName + " " + year, StandardCharsets.UTF_8);
        String searchHtml = fetch(SEARCH_URL + query, BASE_URL + "/");
        if (searchHtml.isEmpty()) return null;

        String bestPath = findBestMatch(searchHtml, movieName, year, matchThreshold);
        if (bestPath == null) return null;

        String pageHtml = fetch(BASE_URL + bestPath, BASE_URL + "/search/?q=" + query);
        if (pageHtml.length() < 200) return null;

        Long revenueUsd = parseWorldwideUsd(pageHtml);
        Long budgetUsd  = parseBudgetUsd(pageHtml);
        if (revenueUsd == null && budgetUsd == null) return null;

        return new BoxOfficeRecord(movieName, year, bestPath, null, null, revenueUsd, budgetUsd,
                                   null, null, null, null, null);
    }

    // ---- private helpers ----

    private String findBestMatch(String html, String targetName, String targetYear,
                                  double threshold) {
        String normTarget = normalize(targetName);
        String bestPath   = null;
        double bestScore  = 0;

        Matcher m = SEARCH_LINK.matcher(html);
        while (m.find()) {
            String href  = m.group(1);
            String label = m.group(2).trim();

            // Extract year from label if present
            Matcher ym = YEAR_IN_TEXT.matcher(label);
            String resultYear = ym.find() ? ym.group(1) : null;

            // Hard-reject on year mismatch when both sides have a year
            if (targetYear != null && resultYear != null && !targetYear.equals(resultYear)) continue;

            String cleanLabel = label.replaceAll("\\s*\\(\\d{4}\\)", "").trim();
            double score = similarity(normTarget, normalize(cleanLabel));
            if (score > bestScore) {
                bestScore = score;
                bestPath  = href;
            }
        }

        return bestScore >= threshold ? bestPath : null;
    }

    private Long parseWorldwideUsd(String html) {
        Matcher m = WW_PATTERN.matcher(html);
        return m.find() ? parseDollar(m.group(1)) : null;
    }

    private Long parseBudgetUsd(String html) {
        Matcher m = BUDGET_PATTERN.matcher(html);
        return m.find() ? parseDollar(m.group(1)) : null;
    }

    private Long parseDollar(String raw) {
        try {
            return Long.parseLong(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String fetch(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         referer)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) return "";
        if (response.statusCode() != 200)
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        return response.body();
    }

    // ---- name normalisation and similarity (mirrors SacnilkCrawlerService) ----

    static String normalize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
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
}
