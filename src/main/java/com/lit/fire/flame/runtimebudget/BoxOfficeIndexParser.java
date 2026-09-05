package com.lit.fire.flame.runtimebudget;

import com.lit.fire.flame.crawler.BoxOfficeRecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses HTML from boxofficeindex.in.
 *
 * Two pages are used, mirroring SacnilkHtmlParser's approach:
 *   1. /sitemap-movies.xml   – discovery: lists all movie detail-page slugs.
 *   2. /movie/{slug}         – detail: runtime (HTML table row) + budget (FAQ JSON-LD block).
 *
 * robots.txt (checked 2026-07-19) disallows only /admin/ and lists a movie sitemap — no
 * Crawl-delay is specified, so the caller applies a polite default delay between requests.
 *
 * Coverage is currently small (~100 movies, all 2025/2026 releases) — this site appears to
 * track only recently-announced/upcoming titles, not a historical catalog.
 */
public class BoxOfficeIndexParser {

    private static final String BASE_URL    = "https://boxofficeindex.in";
    private static final String SITEMAP_URL = BASE_URL + "/sitemap-movies.xml";
    private static final String USER_AGENT  =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Sitemap: <loc>https://boxofficeindex.in/movie/moana-2026</loc>
    private static final Pattern SITEMAP_SLUG = Pattern.compile(
        "<loc>https://boxofficeindex\\.in/movie/([a-z0-9-]+)</loc>"
    );

    // Slug year suffix: "dhurandhar-2025" → "2025"
    private static final Pattern SLUG_YEAR = Pattern.compile("-(\\d{4})$");

    // Detail page – Runtime row: <tr><td ...>Runtime</td><td ...>115 min</td></tr>
    private static final Pattern RUNTIME_ROW = Pattern.compile(
        ">Runtime</td>\\s*<td[^>]*>\\s*([^<]+?)\\s*</td>"
    );

    // Detail page – budget from the FAQPage JSON-LD block:
    // "...was made on an estimated budget of ₹300.00 Cr."
    private static final Pattern BUDGET_FAQ = Pattern.compile(
        "budget of ₹\\s*([\\d,.]+)\\s*Cr", Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;

    public BoxOfficeIndexParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /** Fetches the sitemap and returns all individual movie page slugs, e.g. "dhurandhar-2025". */
    public List<String> fetchMovieSlugsFromSitemap() throws IOException, InterruptedException {
        String xml = fetch(SITEMAP_URL, BASE_URL + "/");
        List<String> slugs = new ArrayList<>();
        Matcher m = SITEMAP_SLUG.matcher(xml);
        while (m.find()) {
            slugs.add(m.group(1));
        }
        return slugs;
    }

    /**
     * Fetches the detail page for a slug and returns a BoxOfficeRecord with runtime/budget.
     * Returns a record with null fields when data is absent or the page doesn't exist.
     */
    public BoxOfficeRecord parseMovieDetailPage(String slug) throws IOException, InterruptedException {
        String url  = BASE_URL + "/movie/" + slug;
        String html = fetch(url, BASE_URL + "/");
        String name = extractNameFromSlug(slug);
        String year = extractYearFromSlug(slug);

        if (html.length() < 200) {
            return new BoxOfficeRecord(name, year, slug, null, null, null, null, null, null, null);
        }

        Integer runtimeMinutes = parseRuntime(html);
        Double  budgetCr       = parseBudget(html);
        return new BoxOfficeRecord(name, year, slug, null, budgetCr, null, null, runtimeMinutes, null, null);
    }

    /** Extracts the movie display name from a slug: "dhurandhar-2025" → "dhurandhar". */
    public String extractNameFromSlug(String slug) {
        return SLUG_YEAR.matcher(slug).replaceFirst("").replace("-", " ").trim();
    }

    /** Extracts the 4-digit year from the slug suffix; returns null if not found. */
    public String extractYearFromSlug(String slug) {
        Matcher m = SLUG_YEAR.matcher(slug);
        return m.find() ? m.group(1) : null;
    }

    // ---- private parsers ----

    private Integer parseRuntime(String html) {
        Matcher m = RUNTIME_ROW.matcher(html);
        if (!m.find()) return null;
        return parseRuntimeString(m.group(1).trim());
    }

    /** Parses "115 min", "2h 30m", or "TBA"/"N/A" (→ null) into total minutes. */
    static Integer parseRuntimeString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.toLowerCase();
        if (lower.equals("tba") || lower.equals("n/a") || lower.equals("na")) return null;

        int minutes = 0;
        Matcher h = Pattern.compile("(\\d+)\\s*h").matcher(raw);
        if (h.find()) minutes += Integer.parseInt(h.group(1)) * 60;
        Matcher min = Pattern.compile("(\\d+)\\s*m(?:in)?").matcher(raw);
        if (min.find()) minutes += Integer.parseInt(min.group(1));
        if (minutes == 0) {
            try { minutes = Integer.parseInt(raw.replaceAll("[^0-9]", "").trim()); }
            catch (NumberFormatException ignored) {}
        }
        return minutes > 0 ? minutes : null;
    }

    private Double parseBudget(String html) {
        Matcher m = BUDGET_FAQ.matcher(html);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Performs a GET request with browser-like headers.
     *
     * HttpRequest.timeout() is not a reliable upper bound in practice (see SacnilkHtmlParser's
     * fetch() for the observed hang) — sendAsync().get(timeout) imposes a hard deadline at the
     * call site instead, independent of whatever went wrong internally.
     */
    private String fetch(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         referer)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        CompletableFuture<HttpResponse<String>> future =
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = future.get(35, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out (hard deadline) fetching " + url);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed fetching " + url, cause);
        }

        if (response.statusCode() == 404) return "";
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }
}
