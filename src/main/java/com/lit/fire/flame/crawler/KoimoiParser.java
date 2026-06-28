package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;

/**
 * Fetches and parses box office data from koimoi.com.
 *
 * Crawl policy (koimoi.com/robots.txt):
 *   - All pages are allowed for the wildcard agent.
 *   - No Crawl-delay is specified; 2 000 ms is used between requests.
 *
 * Strategy:
 *   1. Search for "{movieName} total collection" at /?s=...
 *   2. Find the best-matching article link in the /box-office/ section.
 *   3. Fetch the article page and extract INR Crore values.
 *   4. Conversion to USD is done by the caller (BoxOfficeCrawlerOrchestrator).
 */
public class KoimoiParser {

    private static final String BASE_URL   = "https://www.koimoi.com";
    private static final String SEARCH_URL = BASE_URL + "/?s=";
    static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Box-office article links from Koimoi search results
    private static final Pattern ARTICLE_LINK = Pattern.compile(
        "href=\"(https://www\\.koimoi\\.com/box-office/[^\"]+)\"[^>]*>([^<]*)</a>"
    );

    // Fallback: any Koimoi article mentioning "collection"
    private static final Pattern ARTICLE_LINK_FALLBACK = Pattern.compile(
        "href=\"(https://www\\.koimoi\\.com/[^\"]*(?:collection|box-office)[^\"]*)\""
    );

    // Worldwide / Total collection in INR Crore (various phrasings used across articles)
    private static final Pattern WW_PATTERN = Pattern.compile(
        "(?:Total Worldwide|Worldwide Total|Total(?:\\s+Box\\s+Office)?|Lifetime(?:\\s+Box\\s+Office)?)" +
        "\\s*(?:Box\\s*Office\\s*)?Collection\\s*[:\\-–]?\\s*(?:Rs\\.?|₹|INR)?\\s*([\\d,.]+)\\s*(?:Crore|Cr\\.?|cr)",
        Pattern.CASE_INSENSITIVE
    );

    // Budget in INR Crore
    private static final Pattern BUDGET_PATTERN = Pattern.compile(
        "(?:Production\\s+|Film\\s+|Made\\s+on\\s+a\\s+)?Budget\\s*[:\\-–]?\\s*(?:Rs\\.?|₹|INR)?\\s*([\\d,.]+)\\s*(?:Crore|Cr\\.?|cr)",
        Pattern.CASE_INSENSITIVE
    );

    // Year in article title/URL
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");

    private final HttpClient httpClient;

    public KoimoiParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Searches Koimoi for the movie's box office article and returns a BoxOfficeRecord
     * with INR-Crore values (worldwideCr / budgetCr). Returns null if no data found.
     */
    public BoxOfficeRecord searchAndParse(String movieName, String year, double matchThreshold)
            throws IOException, InterruptedException {
        String query      = URLEncoder.encode(movieName + " total collection", StandardCharsets.UTF_8);
        String searchHtml = fetch(SEARCH_URL + query, BASE_URL + "/");
        if (searchHtml.isEmpty()) return null;

        String bestUrl = findBestArticle(searchHtml, movieName, year, matchThreshold);
        if (bestUrl == null) return null;

        String articleHtml = fetch(bestUrl, SEARCH_URL + query);
        if (articleHtml.length() < 200) return null;

        Double worldwideCr = parseWorldwideCr(articleHtml);
        Double budgetCr    = parseBudgetCr(articleHtml);
        if (worldwideCr == null && budgetCr == null) return null;

        return new BoxOfficeRecord(movieName, year, bestUrl, worldwideCr, budgetCr);
    }

    // ---- private helpers ----

    private String findBestArticle(String html, String targetName, String targetYear,
                                    double threshold) {
        String normTarget = normalize(targetName);
        String bestUrl    = null;
        double bestScore  = 0;

        List<String[]> candidates = new ArrayList<>(); // [url, title/label]

        Matcher m = ARTICLE_LINK.matcher(html);
        while (m.find()) candidates.add(new String[]{m.group(1), m.group(2).trim()});

        // Fallback: grab all collection links without a label
        if (candidates.isEmpty()) {
            Matcher mf = ARTICLE_LINK_FALLBACK.matcher(html);
            while (mf.find()) candidates.add(new String[]{mf.group(1), ""});
        }

        for (String[] candidate : candidates) {
            String url   = candidate[0];
            String label = candidate[1];

            // Year check from URL or label
            Matcher ym = YEAR_PATTERN.matcher(url + " " + label);
            String resultYear = null;
            while (ym.find()) {
                String y = ym.group(1);
                if (y.startsWith("20") || y.startsWith("19")) { resultYear = y; break; }
            }
            if (targetYear != null && resultYear != null && !targetYear.equals(resultYear)) continue;

            // Strip noise words from label for name comparison
            String cleanLabel = label
                .replaceAll("(?i)\\s*(total|box\\s*office|collection|week\\s*\\d+.*|day\\s*\\d+.*)\\s*", " ")
                .trim();
            String normLabel = label.isEmpty()
                ? normalize(url.replaceAll(".*/", "").replaceAll("-", " "))
                : normalize(cleanLabel);

            double score = similarity(normTarget, normLabel);
            if (score > bestScore) {
                bestScore = score;
                bestUrl   = url;
            }
        }

        return bestScore >= threshold ? bestUrl : null;
    }

    private Double parseWorldwideCr(String html) {
        Matcher m = WW_PATTERN.matcher(html);
        return m.find() ? parseAmount(m.group(1)) : null;
    }

    private Double parseBudgetCr(String html) {
        Matcher m = BUDGET_PATTERN.matcher(html);
        return m.find() ? parseAmount(m.group(1)) : null;
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
