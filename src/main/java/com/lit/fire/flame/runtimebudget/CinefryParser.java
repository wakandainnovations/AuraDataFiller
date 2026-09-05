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
 * Fetches and parses HTML from cinefry.co.in.
 *
 * Unlike sacnilk/boxofficeindex, cinefry has no dedicated movie sitemap — it's a general
 * WordPress blog (actor biographies, news, etc.) whose Yoast SEO sitemap index points at
 * several generic post-sitemap*.xml files. Financial articles are identifiable only by a
 * URL-slug suffix convention ("-movie-box-office-collection", "-box-office-collection",
 * "-movie-budget", "-budget"), so discovery here is: fetch the sitemap index, fetch each
 * post-sitemap, and keep only URLs matching that suffix.
 *
 * robots.txt (checked 2026-07-19) disallows /admin/, /privacy-policy/, /terms-and-conditions/,
 * and /feed/ — none of which affect these article URLs or the sitemaps. No Crawl-delay is
 * specified, so the caller applies a polite default delay between requests.
 *
 * Detail pages carry a "Movie Info" table (present on some but not all articles — cinefry's
 * coverage is inconsistent per-title) with rows like:
 *   <tr><td><strong>Budget</strong></td><td>₹10 Crores (estimated)</td></tr>
 *   <tr><td><strong>Runtime</strong></td><td>TBA</td></tr>
 * In practice runtime is almost always "TBA" on this site — budget is the more useful field.
 */
public class CinefryParser {

    private static final String BASE_URL         = "https://www.cinefry.co.in";
    private static final String SITEMAP_INDEX_URL = BASE_URL + "/sitemap_index.xml";
    private static final String USER_AGENT       =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Sitemap index: <loc>https://www.cinefry.co.in/post-sitemap3.xml</loc>
    private static final Pattern POST_SITEMAP_LOC = Pattern.compile(
        "<loc>(https://www\\.cinefry\\.co\\.in/post-sitemap\\d*\\.xml)</loc>"
    );

    // Any URL in a post-sitemap: <loc>https://www.cinefry.co.in/some-slug/</loc>
    private static final Pattern PAGE_LOC = Pattern.compile(
        "<loc>(https://www\\.cinefry\\.co\\.in/([a-z0-9-]+)/?)</loc>"
    );

    // Financial-article slug suffix: optional trailing language qualifier + optional "movie-" +
    // either "box-office-collection" or "budget". Matches e.g.:
    //   "madharas-mafia-company-movie-box-office-collection" (no language)
    //   "mask-tamil-movie-budget"                            (language="tamil")
    //   "dusshera-gujarathi-movie-budget"                    (language="gujarathi")
    private static final Pattern FINANCIAL_SLUG_SUFFIX = Pattern.compile(
        "-(?:(?:tamil|telugu|hindi|malayalam|kannada|bengali|gujarati|gujarathi|punjabi|" +
        "marathi|bhojpuri|odia|oriya|urdu|english)-)?(?:movie-)?(?:box-office-collection|budget)$",
        Pattern.CASE_INSENSITIVE
    );

    // Detail page – Movie Info table rows: <tr><td><strong>Label</strong></td><td>Value</td></tr>
    private static final Pattern BUDGET_ROW = Pattern.compile(
        "<tr><td><strong>Budget</strong></td><td>\\s*([^<]+?)\\s*</td></tr>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RUNTIME_ROW = Pattern.compile(
        "<tr><td><strong>Runtime</strong></td><td>\\s*([^<]+?)\\s*</td></tr>", Pattern.CASE_INSENSITIVE
    );

    // Budget cell value: "₹10 Crores (estimated)" / "₹80 Crores (Estimated)" / "₹8 Crores"
    private static final Pattern BUDGET_AMOUNT = Pattern.compile(
        "₹\\s*([\\d,.]+)\\s*Crores?", Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;

    public CinefryParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Fetches the sitemap index, then every post-sitemap it references, and returns the full
     * URLs of articles matching the financial-article slug suffix convention.
     */
    public List<String> fetchFinancialArticleUrls() throws IOException, InterruptedException {
        String indexXml = fetch(SITEMAP_INDEX_URL, BASE_URL + "/");
        List<String> postSitemaps = new ArrayList<>();
        Matcher idx = POST_SITEMAP_LOC.matcher(indexXml);
        while (idx.find()) postSitemaps.add(idx.group(1));

        List<String> urls = new ArrayList<>();
        for (String sitemapUrl : postSitemaps) {
            String xml = fetch(sitemapUrl, BASE_URL + "/");
            Matcher m = PAGE_LOC.matcher(xml);
            while (m.find()) {
                String slug = m.group(2);
                if (FINANCIAL_SLUG_SUFFIX.matcher(slug).find()) {
                    urls.add(m.group(1));
                }
            }
        }
        return urls;
    }

    /** Extracts a fuzzy-matchable movie name from an article URL's final path segment. */
    public String extractNameFromUrl(String url) {
        String slug = url.replaceAll("/$", "");
        slug = slug.substring(slug.lastIndexOf('/') + 1);
        slug = FINANCIAL_SLUG_SUFFIX.matcher(slug).replaceFirst("");
        return slug.replace("-", " ").trim();
    }

    /**
     * Fetches an article page and returns a BoxOfficeRecord with runtime/budget.
     * cinefry slugs carry no release year, so the returned year is always null — callers
     * must match by name only (year-agnostic), same limitation KoimoiParser has when a
     * search result carries no year either.
     */
    public BoxOfficeRecord parseArticlePage(String url) throws IOException, InterruptedException {
        String html = fetch(url, BASE_URL + "/");
        String name = extractNameFromUrl(url);

        if (html.length() < 200) {
            return new BoxOfficeRecord(name, null, url, null, null, null, null, null, null, null);
        }

        Integer runtimeMinutes = parseRuntime(html);
        Double  budgetCr       = parseBudget(html);
        return new BoxOfficeRecord(name, null, url, null, budgetCr, null, null, runtimeMinutes, null, null);
    }

    // ---- private parsers ----

    private Integer parseRuntime(String html) {
        Matcher m = RUNTIME_ROW.matcher(html);
        if (!m.find()) return null;
        return parseRuntimeString(m.group(1).trim());
    }

    /** Parses "2h 15m", "135 minutes", or "TBA"/"N/A" (→ null) into total minutes. */
    static Integer parseRuntimeString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.toLowerCase();
        if (lower.equals("tba") || lower.equals("n/a") || lower.equals("na")) return null;

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
        Matcher row = BUDGET_ROW.matcher(html);
        if (!row.find()) return null;
        String cell = row.group(1);
        if (cell.equalsIgnoreCase("TBA") || cell.equalsIgnoreCase("N/A")) return null;

        Matcher amount = BUDGET_AMOUNT.matcher(cell);
        if (!amount.find()) return null;
        try {
            return Double.parseDouble(amount.group(1).replace(",", "").trim());
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
