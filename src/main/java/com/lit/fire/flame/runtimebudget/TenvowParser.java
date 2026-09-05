package com.lit.fire.flame.runtimebudget;

import com.lit.fire.flame.crawler.BoxOfficeRecord;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses HTML from tenvow.com via its WordPress search endpoint.
 *
 * tenvow's article slugs are too inconsistent to reconstruct a movie name from (mixed
 * suffixes like "-box-office-collection", "-collection-day-wise-ww", "-movie-collection-n-
 * budget", etc.), unlike sacnilk/boxofficeindex's clean "{name}-{year}" convention. Its default
 * WordPress search (/?s=query) returns clean, relevance-ranked article titles instead, so
 * discovery here mirrors KoimoiParser's live-search strategy rather than a sitemap crawl.
 *
 * robots.txt (checked 2026-07-19) disallows only /wp-admin/ (explicitly allowing
 * admin-ajax.php) — the search endpoint and /box-office/ articles are unaffected. No
 * Crawl-delay is specified, so the caller applies a polite default delay between requests.
 *
 * Detail pages carry a "Movie Info" list block (present only on some, richer articles):
 *   <li><strong>Running Time:</strong> ~159 minutes (2h 39m)</li>
 *   <li><strong>Budget:</strong> ₹47 crore</li>
 * with a secondary FAQ-style fallback for budget on simpler articles:
 *   <strong>What is the Budget of X</strong></p><p class="wp-block-paragraph">₹150 crore</p>
 * No fallback exists for runtime — when the list block is absent, runtime is simply unavailable.
 */
public class TenvowParser {

    private static final String BASE_URL   = "https://tenvow.com";
    private static final String SEARCH_URL = BASE_URL + "/?s=";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Search results: only /box-office/ article links carry box-office/financial data.
    private static final Pattern ARTICLE_LINK = Pattern.compile(
        "href=\"(https://tenvow\\.com/box-office/[^\"]+)\"[^>]*>([^<]*)</a>"
    );

    // Year, if present anywhere in the URL or label (many tenvow slugs omit it entirely).
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");

    // Detail page – "Movie Info" list block.
    private static final Pattern RUNNING_TIME = Pattern.compile(
        "<li><strong>Running Time:?</strong>\\s*([^<]+?)\\s*</li>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BUDGET_LIST = Pattern.compile(
        "<li><strong>Budget:?</strong>\\s*₹\\s*([\\d,.]+)\\s*crores?", Pattern.CASE_INSENSITIVE
    );

    // Fallback: FAQ-style "What is the Budget of X" heading followed by a plain-text answer.
    private static final Pattern BUDGET_FAQ = Pattern.compile(
        "What is the Budget of[^<]*</strong></p>\\s*<p class=\"wp-block-paragraph\">\\s*₹\\s*([\\d,.]+)\\s*crores?",
        Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;

    public TenvowParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Searches tenvow for the movie's box-office article and returns a BoxOfficeRecord with
     * runtime/budget. Returns null if no sufficiently-similar result is found or the matched
     * page has neither field.
     */
    public BoxOfficeRecord searchAndParse(String movieName, String year, double matchThreshold)
            throws IOException, InterruptedException {
        String query      = URLEncoder.encode(movieName, StandardCharsets.UTF_8);
        String searchHtml = fetch(SEARCH_URL + query, BASE_URL + "/");
        if (searchHtml.isEmpty()) return null;

        String bestUrl = findBestArticle(searchHtml, movieName, year, matchThreshold);
        if (bestUrl == null) return null;

        String articleHtml = fetch(bestUrl, SEARCH_URL + query);
        if (articleHtml.length() < 200) return null;

        Integer runtimeMinutes = parseRuntime(articleHtml);
        Double  budgetCr       = parseBudget(articleHtml);
        if (runtimeMinutes == null && budgetCr == null) return null;

        return new BoxOfficeRecord(movieName, year, bestUrl, null, budgetCr, null, null,
                                   runtimeMinutes, null, null);
    }

    // ---- private helpers ----

    private String findBestArticle(String html, String targetName, String targetYear,
                                    double threshold) {
        String normTarget = normalize(targetName);
        String bestUrl    = null;
        double bestScore  = 0;

        Matcher m = ARTICLE_LINK.matcher(html);
        while (m.find()) {
            String url   = m.group(1);
            String label = m.group(2).trim();
            if (label.isEmpty()) continue;

            // Hard-reject on year mismatch only when the label/URL actually carries a year —
            // most tenvow slugs/titles omit it, so this check is best-effort, same as Koimoi.
            Matcher ym = YEAR_PATTERN.matcher(url + " " + label);
            String resultYear = ym.find() ? ym.group() : null;
            if (targetYear != null && resultYear != null && !targetYear.equals(resultYear)) continue;

            String cleanLabel = label
                .replaceAll("(?i)\\s*[|–-]\\s*(hit|flop|box\\s*office|collection|day\\s*wise|" +
                            "n\\s+budget|budget|ww|worldwide|review|ott).*$", "")
                .replaceAll("(?i)\\bmovie\\b", "")
                .trim();
            double score = similarity(normTarget, normalize(cleanLabel));
            if (score > bestScore) {
                bestScore = score;
                bestUrl   = url;
            }
        }

        return bestScore >= threshold ? bestUrl : null;
    }

    private Integer parseRuntime(String html) {
        Matcher m = RUNNING_TIME.matcher(html);
        if (!m.find()) return null;
        return parseRuntimeString(m.group(1).trim());
    }

    /** Parses "~159 minutes (2h 39m)" preferring the parenthesised "XhYm" form when present. */
    static Integer parseRuntimeString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int minutes = 0;
        Matcher h = Pattern.compile("(\\d+)\\s*h").matcher(raw);
        if (h.find()) minutes += Integer.parseInt(h.group(1)) * 60;
        Matcher min = Pattern.compile("(\\d+)\\s*m(?:in(?:ute)?s?)?").matcher(raw);
        if (min.find()) minutes += Integer.parseInt(min.group(1));
        if (minutes == 0) {
            try { minutes = Integer.parseInt(raw.replaceAll("[^0-9]", "").trim()); }
            catch (NumberFormatException ignored) {}
        }
        return minutes > 0 ? minutes : null;
    }

    private Double parseBudget(String html) {
        Matcher m = BUDGET_LIST.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        m = BUDGET_FAQ.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    private Double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
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

    // ---- name normalisation and similarity (mirrors KoimoiParser/SacnilkCrawlerService) ----

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
