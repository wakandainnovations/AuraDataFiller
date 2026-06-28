package com.lit.fire.flame.crawler;

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
 * Fetches and parses HTML from sacnilk.com.
 *
 * Two pages are used:
 *   1. /sitemap-movies.xml   – discovery: lists all movie slugs (~1000 entries)
 *   2. /movie/{slug}         – detail: worldwide collection and budget for each film
 *
 * robots.txt allows all crawling with a 1-second crawl-delay.
 * The actual delay between requests is controlled by the caller (SacnilkCrawlerService).
 */
public class SacnilkHtmlParser {

    private static final String BASE_URL    = "https://www.sacnilk.com";
    private static final String SITEMAP_URL = BASE_URL + "/sitemap-movies.xml";
    private static final String USER_AGENT  =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ---- Compiled patterns (shared across instances) ----

    // Sitemap: <loc>https://www.sacnilk.com/movie/Slug_2022</loc>
    private static final Pattern SITEMAP_SLUG = Pattern.compile(
        "<loc>https://www\\.sacnilk\\.com/movie/([A-Za-z0-9_]+)</loc>"
    );

    // Slug year suffix: "KGF_Chapter_2_2022" → "2022"
    private static final Pattern SLUG_YEAR = Pattern.compile("_(\\d{4})$");

    // Detail page – Worldwide collection from the collection card section.
    // HTML: >Worldwide</div>  ...whitespace...  ₹120.72 Cr</div>
    private static final Pattern WW_CARD = Pattern.compile(
        ">Worldwide</div>\\s*<div[^>]*>\\s*₹\\s*([\\d,.]+)\\s*Cr\\s*</div>",
        Pattern.DOTALL
    );

    // Detail page – Worldwide from the breakdown tile where the value precedes the label.
    // HTML: ₹120.72 Cr</div>  ...  >Total Worldwide</div>
    private static final Pattern WW_BREAKDOWN = Pattern.compile(
        "₹\\s*([\\d,.]+)\\s*Cr\\s*</div>(?:[\\s\\S]{0,200}?)>Total Worldwide</div>"
    );

    // Detail page – Budget from the quick-stats sidebar.
    // HTML: >Budget:</span>  <span ...>₹150 Cr</span>
    // When budget is unavailable the span contains "N/A" which won't match this pattern.
    private static final Pattern BUDGET = Pattern.compile(
        ">Budget:</span>\\s*<span[^>]*>\\s*₹\\s*([\\d,.]+)\\s*Cr\\s*</span>",
        Pattern.DOTALL
    );

    private final HttpClient httpClient;

    public SacnilkHtmlParser() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Fetches the sitemap and returns all individual movie page slugs.
     * Example slug: "KGF_Chapter_2_2022"
     */
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
     * Fetches the detail page for a slug and returns a BoxOfficeRecord.
     * Returns a record with null worldwideCr and budgetCr when data is absent.
     */
    public BoxOfficeRecord parseMovieDetailPage(String slug) throws IOException, InterruptedException {
        String url  = BASE_URL + "/movie/" + slug;
        String html = fetch(url, BASE_URL + "/");

        // Validate that we received actual HTML (unrecognised pages return ≤10 bytes)
        if (html.length() < 200) {
            return new BoxOfficeRecord(extractNameFromSlug(slug), extractYearFromSlug(slug),
                                       slug, null, null);
        }

        Double worldwideCr = parseWorldwide(html);
        Double budgetCr    = parseBudget(html);
        return new BoxOfficeRecord(extractNameFromSlug(slug), extractYearFromSlug(slug),
                                   slug, worldwideCr, budgetCr);
    }

    /** Extracts the movie display name from a slug: "KGF_Chapter_2_2022" → "KGF Chapter 2". */
    public String extractNameFromSlug(String slug) {
        String withoutYear = slug.replaceAll("_\\d{4}$", "");
        return withoutYear.replace("_", " ").trim();
    }

    /** Extracts the 4-digit year from the slug suffix; returns null if not found. */
    public String extractYearFromSlug(String slug) {
        Matcher m = SLUG_YEAR.matcher(slug);
        return m.find() ? m.group(1) : null;
    }

    // ---- private helpers ----

    private Double parseWorldwide(String html) {
        Matcher m = WW_CARD.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        m = WW_BREAKDOWN.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    private Double parseBudget(String html) {
        Matcher m = BUDGET.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    /** Parses "1,200.50" or "120.72" (stripped of commas) into a Double. */
    private Double parseAmount(String raw) {
        String cleaned = raw.replace(",", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Performs a GET request with browser-like headers.
     * sacnilk.com is behind Cloudflare and returns an empty body for requests
     * without a Referer, so we always send one.
     */
    private String fetch(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         referer)
            .header("Connection",      "keep-alive")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            return ""; // movie page not found – caller treats empty body gracefully
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }
}
