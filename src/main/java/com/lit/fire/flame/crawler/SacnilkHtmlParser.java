package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses HTML from sacnilk.com.
 *
 * Two pages are used:
 *   1. /sitemap-movies.xml   – discovery: lists all movie slugs (~1000 entries)
 *   2. /movie/{slug}         – detail: collection, budget, genre, language, runtime, rating, status
 *
 * robots.txt allows all crawling with a 1-second crawl-delay.
 * The actual delay between requests is controlled by the caller (SacnilkCrawlerService).
 * HTTP/1.1 is used explicitly to bypass Cloudflare's HTTP/2 fingerprint checks.
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

    // Worldwide from the text section: "Worldwide Collection ₹ 46.95 Cr ##"
    private static final Pattern WW_TEXT = Pattern.compile(
        "Worldwide(?:\\s+Total(?:\\s+Gross)?)?\\s+Collection\\s*₹\\s*([\\d,.]+)\\s*Cr"
    );

    // Detail page – Budget from the quick-stats sidebar.
    // HTML: >Budget:</span>  <span ...>₹150 Cr</span>
    private static final Pattern BUDGET = Pattern.compile(
        ">Budget:</span>\\s*<span[^>]*>\\s*₹\\s*([\\d,.]+)\\s*Cr\\s*</span>",
        Pattern.DOTALL
    );

    // Genre from the info section.
    // HTML: >Genre:</span> <span ...>Action, Drama, Thriller</span>
    private static final Pattern GENRE_HTML = Pattern.compile(
        ">Genre:</span>\\s*<span[^>]*>\\s*([^<]+?)\\s*</span>",
        Pattern.DOTALL
    );

    // Genre from JSON-LD: "genre": ["Action", "Drama"]
    private static final Pattern GENRE_JSON = Pattern.compile(
        "\"@type\"\\s*:\\s*\"Movie\"[\\s\\S]{0,600}?\"genre\"\\s*:\\s*\\[([^\\]]+)\\]"
    );

    // Language from the info section.
    // HTML: >Languages:</span> <span ...>Hindi, Telugu</span>
    private static final Pattern LANG_HTML = Pattern.compile(
        ">Languages?:</span>\\s*<span[^>]*>\\s*([^<]+?)\\s*</span>",
        Pattern.DOTALL
    );

    // Language from JSON-LD: "inLanguage": ["Hindi"]
    private static final Pattern LANG_JSON = Pattern.compile(
        "\"@type\"\\s*:\\s*\"Movie\"[\\s\\S]{0,600}?\"inLanguage\"\\s*:\\s*\\[([^\\]]+)\\]"
    );

    // Runtime from the info section.
    // HTML: >Runtime:</span> <span ...>2h 30m</span>  or  <span ...>N/A</span>
    private static final Pattern RUNTIME_HTML = Pattern.compile(
        ">Runtime:</span>\\s*<span[^>]*>\\s*([^<]+?)\\s*</span>",
        Pattern.DOTALL
    );

    // Runtime from JSON-LD: "duration": "PT2H30M"  (ISO 8601 duration)
    private static final Pattern RUNTIME_ISO = Pattern.compile(
        "\"duration\"\\s*:\\s*\"PT(?:(\\d+)H)?(?:(\\d+)M)?\""
    );

    // User rating display: <span class="...text-orange-600">4.7</span>
    private static final Pattern USER_RATING = Pattern.compile(
        "font-bold text-orange-600\">([\\d.]+)</span>"
    );

    // Sacnilk review rating from JSON-LD: "ratingValue": 5
    private static final Pattern REVIEW_RATING = Pattern.compile(
        "\"ratingValue\"\\s*:\\s*([\\d.]+)"
    );

    // Release date from JSON-LD Movie type: "datePublished": "2022-04-14"
    private static final Pattern MOVIE_DATE = Pattern.compile(
        "\"@type\"\\s*:\\s*\"Movie\"[\\s\\S]{0,600}?\"datePublished\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\""
    );

    // Release status from Quick Stats: >Release Status:</span> <span ...>Released 2 days ago</span>
    private static final Pattern RELEASE_STATUS = Pattern.compile(
        ">Release Status:</span>\\s*<span[^>]*>\\s*([^<]+?)\\s*</span>",
        Pattern.DOTALL
    );

    private final HttpClient httpClient;

    public SacnilkHtmlParser() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // HTTP/1.1 bypasses Cloudflare JA3 fingerprint check
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
     * Fetches the detail page for a slug and returns a BoxOfficeRecord with all
     * available data: worldwide collection, budget, genre, language, runtime, rating, status.
     * Returns a record with null fields when data is absent.
     */
    public BoxOfficeRecord parseMovieDetailPage(String slug) throws IOException, InterruptedException {
        String url  = BASE_URL + "/movie/" + slug;
        String html = fetch(url, BASE_URL + "/");

        // Validate that we received actual HTML (unrecognised pages return ≤10 bytes)
        if (html.length() < 200) {
            return new BoxOfficeRecord(extractNameFromSlug(slug), extractYearFromSlug(slug),
                                       slug, null, null);
        }

        Double  worldwideCr    = parseWorldwide(html);
        Double  budgetCr       = parseBudget(html);
        String  genre          = parseGenre(html);
        String  language       = parseLanguage(html);
        Integer runtimeMinutes = parseRuntime(html);
        Double  rating         = parseRating(html);
        String  status         = parseStatus(html);

        return new BoxOfficeRecord(extractNameFromSlug(slug), extractYearFromSlug(slug),
                                   slug, worldwideCr, budgetCr,
                                   genre, language, runtimeMinutes, rating, status);
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

    // ---- private parsers ----

    private Double parseWorldwide(String html) {
        Matcher m = WW_CARD.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        m = WW_BREAKDOWN.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        m = WW_TEXT.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    private Double parseBudget(String html) {
        Matcher m = BUDGET.matcher(html);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    private String parseGenre(String html) {
        // HTML info section takes priority
        Matcher m = GENRE_HTML.matcher(html);
        if (m.find()) {
            String val = m.group(1).trim();
            if (!val.isEmpty() && !val.equalsIgnoreCase("N/A")) return val;
        }
        // Fallback: JSON-LD genre array → "Action", "Drama" → "Action, Drama"
        m = GENRE_JSON.matcher(html);
        if (m.find()) {
            String arr = m.group(1);
            String joined = arr.replaceAll("\"", "").replaceAll(",\\s*", ", ").trim();
            if (!joined.isEmpty()) return joined;
        }
        return null;
    }

    private String parseLanguage(String html) {
        // HTML info section takes priority
        Matcher m = LANG_HTML.matcher(html);
        if (m.find()) {
            String val = m.group(1).trim();
            if (!val.isEmpty() && !val.equalsIgnoreCase("N/A")) return val;
        }
        // Fallback: JSON-LD inLanguage array
        m = LANG_JSON.matcher(html);
        if (m.find()) {
            String arr = m.group(1);
            String joined = arr.replaceAll("\"", "").replaceAll(",\\s*", ", ").trim();
            if (!joined.isEmpty()) return joined;
        }
        return null;
    }

    private Integer parseRuntime(String html) {
        // JSON-LD ISO 8601 duration takes priority: "PT2H30M"
        Matcher m = RUNTIME_ISO.matcher(html);
        if (m.find()) {
            String hStr = m.group(1);
            String minStr = m.group(2);
            int total = 0;
            if (hStr   != null) total += Integer.parseInt(hStr) * 60;
            if (minStr != null) total += Integer.parseInt(minStr);
            if (total > 0) return total;
        }
        // HTML info section: "2h 30m", "150 mins", "N/A"
        m = RUNTIME_HTML.matcher(html);
        if (m.find()) {
            return parseRuntimeString(m.group(1).trim());
        }
        return null;
    }

    static Integer parseRuntimeString(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("N/A")) return null;
        int minutes = 0;
        Matcher h = Pattern.compile("(\\d+)\\s*h").matcher(raw);
        if (h.find()) minutes += Integer.parseInt(h.group(1)) * 60;
        Matcher min = Pattern.compile("(\\d+)\\s*m(?:in)?").matcher(raw);
        if (min.find()) minutes += Integer.parseInt(min.group(1));
        if (minutes == 0) {
            // Try plain number (assume minutes)
            try { minutes = Integer.parseInt(raw.replaceAll("[^0-9]", "").trim()); }
            catch (NumberFormatException ignored) {}
        }
        return minutes > 0 ? minutes : null;
    }

    private Double parseRating(String html) {
        // User rating from the orange display box (sacnilk community rating)
        Matcher m = USER_RATING.matcher(html);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        // Fallback: sacnilk editorial rating from JSON-LD review
        m = REVIEW_RATING.matcher(html);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String parseStatus(String html) {
        // Explicit "Release Status:" label in Quick Stats
        Matcher m = RELEASE_STATUS.matcher(html);
        if (m.find()) {
            String text = m.group(1).trim().toLowerCase();
            if (text.contains("released") || text.contains("days ago") || text.contains("weeks ago")) {
                return "Released";
            }
            if (text.contains("upcoming") || text.contains("releasing")) {
                return "Upcoming";
            }
        }
        // Fall back to release date from JSON-LD
        m = MOVIE_DATE.matcher(html);
        if (m.find()) {
            try {
                LocalDate releaseDate = LocalDate.parse(m.group(1));
                return releaseDate.isAfter(LocalDate.now()) ? "Upcoming" : "Released";
            } catch (DateTimeParseException ignored) {}
        }
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
     * Performs a GET request with browser-like headers using HTTP/1.1.
     * sacnilk.com is behind Cloudflare and returns an empty body for requests
     * without a Referer, so we always send one.
     */
    private String fetch(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .version(HttpClient.Version.HTTP_1_1)
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         referer)
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
