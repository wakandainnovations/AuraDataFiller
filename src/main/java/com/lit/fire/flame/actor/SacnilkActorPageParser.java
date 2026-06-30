package com.lit.fire.flame.actor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * Fetches and parses sacnilk.com actor filmography pages.
 *
 * Discovery:  sitemap-news.xml (or main sitemap sub-sitemaps) are scanned for
 *             URLs matching /news/List_Of_All_*_Movies.
 * robots.txt: crawl-delay is read and returned to the caller; the caller is
 *             responsible for honouring it between requests.
 * HTTP/1.1:   forced to bypass Cloudflare JA3 fingerprinting (same as movie crawler).
 */
public class SacnilkActorPageParser {

    private static final String BASE_URL   = "https://www.sacnilk.com";
    private static final String ROBOTS_URL = BASE_URL + "/robots.txt";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // robots.txt crawl-delay line
    private static final Pattern CRAWL_DELAY = Pattern.compile("(?i)crawl-delay:\\s*(\\d+)");

    // Sitemap: <loc>https://www.sacnilk.com/news/List_Of_All_Akshay_Kumra_Movies</loc>
    //          also handles paginated slugs like List_Of_All_Akshay_Kumra_Movies_2
    private static final Pattern ACTOR_FILM_URL = Pattern.compile(
        "<loc>(https://www\\.sacnilk\\.com/news/List_Of_All_([A-Za-z0-9_]+?)_Movies(?:_\\d+)?)</loc>"
    );

    // Sub-sitemap references in main sitemap.xml
    private static final Pattern SUB_SITEMAP = Pattern.compile(
        "<loc>(https://www\\.sacnilk\\.com/sitemap[^<]+\\.xml)</loc>"
    );

    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "<table[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TR_PATTERN = Pattern.compile(
        "<tr[^>]*>(.*?)</tr>",    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TD_PATTERN = Pattern.compile(
        "<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN = Pattern.compile(
        "href=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG      = Pattern.compile("<[^>]+>");
    private static final Pattern MOVIE_SLUG_PAT = Pattern.compile("/movie/([A-Za-z0-9_]+)");
    // Year must be 1980-2035 range
    private static final Pattern YEAR_PAT = Pattern.compile("\\b((?:19[89]\\d|20[0-3]\\d))\\b");
    private static final Pattern SLUG_YEAR  = Pattern.compile("_(\\d{4})$");

    private final HttpClient httpClient;

    public SacnilkActorPageParser() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Fetches robots.txt and returns the declared Crawl-delay in milliseconds.
     * Returns 1000 ms if the value is absent or the fetch fails.
     */
    public long fetchCrawlDelayMs() {
        try {
            String body = fetch(ROBOTS_URL, BASE_URL + "/");
            Matcher m = CRAWL_DELAY.matcher(body);
            if (m.find()) return Long.parseLong(m.group(1)) * 1000L;
        } catch (Exception ignored) {}
        return 1000L;
    }

    /**
     * Constructs the expected sacnilk filmography URL for a given actor name.
     *
     * "Akshay Kumar"   → https://www.sacnilk.com/news/List_Of_All_Akshay_Kumar_Movies
     * "A.R. Rahman"    → https://www.sacnilk.com/news/List_Of_All_A_R_Rahman_Movies
     * "Shah Rukh Khan" → https://www.sacnilk.com/news/List_Of_All_Shah_Rukh_Khan_Movies
     */
    public String constructFilmographyUrl(String actorName) {
        return BASE_URL + "/news/List_Of_All_" + filmographySlug(actorName) + "_Movies";
    }

    /**
     * Returns a URL variant with the last two characters of the name slug transposed.
     * Catches sacnilk typos like "Akshay Kumar" → slug "Akshay_Kumar" → typo "Akshay_Kumra".
     * Returns null if no distinct variant can be formed (same result, or boundary is underscore).
     */
    public String constructTypoVariantUrl(String actorName) {
        String slug = filmographySlug(actorName);
        int len = slug.length();
        if (len < 2) return null;
        char last   = slug.charAt(len - 1);
        char penult = slug.charAt(len - 2);
        if (last == '_' || penult == '_' || last == penult) return null;
        String transposed = slug.substring(0, len - 2) + last + penult;
        return BASE_URL + "/news/List_Of_All_" + transposed + "_Movies";
    }

    private String filmographySlug(String actorName) {
        return actorName.trim()
            .replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    /**
     * Scans sacnilk sitemaps for actor filmography page URLs.
     * NOTE: as of 2026, sacnilk does not index filmography pages in their sitemap;
     * this method is kept as a future-proof supplement and usually returns an empty map.
     *
     * @return map of URL-slug → full URL
     *         (e.g. "Akshay_Kumra" → "https://www.sacnilk.com/news/List_Of_All_Akshay_Kumra_Movies")
     */
    public Map<String, String> discoverActorFilmographyUrls() throws IOException, InterruptedException {
        Map<String, String> result = new LinkedHashMap<>();

        for (String url : List.of(
                BASE_URL + "/sitemap-news.xml",
                BASE_URL + "/sitemap-articles.xml",
                BASE_URL + "/news-sitemap.xml")) {
            collectFromSitemap(url, result);
        }

        if (result.isEmpty()) {
            try {
                String main = fetch(BASE_URL + "/sitemap.xml", BASE_URL + "/");
                Matcher m = SUB_SITEMAP.matcher(main);
                while (m.find()) collectFromSitemap(m.group(1), result);
            } catch (IOException ignored) {}
        }

        return result;
    }

    private void collectFromSitemap(String url, Map<String, String> out)
            throws IOException, InterruptedException {
        try {
            String xml = fetch(url, BASE_URL + "/");
            Matcher m = ACTOR_FILM_URL.matcher(xml);
            while (m.find()) out.put(m.group(2), m.group(1)); // slug → url
        } catch (IOException ignored) {} // missing sitemap — skip silently
    }

    /**
     * Fetches an actor filmography page and parses all post-1980 movie entries.
     *
     * Non-existent actor pages on sacnilk return a 302 redirect to the homepage.
     * The HttpClient follows this automatically, so we detect the redirect by checking
     * that the final HTML contains "List Of All" or "List_Of_All" — real filmography
     * pages always have this in their title/og:url/heading; the homepage does not.
     *
     * @param actorName the canonical name from the DB (set on each returned entry)
     * @param url       full URL of the sacnilk filmography page
     */
    public List<ActorMovieEntry> parseActorFilmography(String actorName, String url)
            throws IOException, InterruptedException {
        String html = fetch(url, BASE_URL + "/news/");
        if (html.length() < 500) return List.of();

        // Reject homepage redirects: non-existent actor pages 302-redirect to sacnilk.com/
        // The homepage contains "List Of All" in sidebar links but its og:url is "https://www.sacnilk.com/"
        // Real filmography pages have og:url that contains "List_Of_All"
        Matcher ogUrlMatcher = Pattern.compile("\"og:url\"[^>]*content=\"([^\"]+)\"").matcher(html);
        if (!ogUrlMatcher.find() || !ogUrlMatcher.group(1).contains("List_Of_All")) return List.of();

        // Verify the page is for the correct actor by comparing the og:title actor name
        // to our DB actor name using fuzzy matching.
        // Title format: "List Of All {ActorName} Movies | ..."
        // This catches URL-construction false matches (e.g., "A.R.S." → Scarlett Johansson's page).
        Matcher ogTitleMatcher = Pattern.compile(
            "\"og:title\" content=\"List Of All ([^|\"]+?) Movies").matcher(html);
        if (ogTitleMatcher.find()) {
            String sacnilkActor = ogTitleMatcher.group(1).trim();
            double score = titleSimilarity(actorName, sacnilkActor);
            if (score < 0.60) return List.of(); // Different actor on this page
        }

        List<ActorMovieEntry> entries = parseFromTables(actorName, html);
        if (entries.isEmpty()) entries = parseFromListItems(actorName, html);

        // Keep only post-1980 (and remove entries with completely unknown dates)
        entries.removeIf(e -> {
            if (e.releaseDate() == null) return true; // no date — skip
            String y = e.releaseDate().substring(0, Math.min(4, e.releaseDate().length()));
            try { return Integer.parseInt(y) <= 1980; }
            catch (NumberFormatException ex) { return true; }
        });
        return entries;
    }

    // ---- HTML table parser ----

    private List<ActorMovieEntry> parseFromTables(String actorName, String html) {
        List<ActorMovieEntry> result = new ArrayList<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(html);

        while (tableMatcher.find()) {
            String tbl = tableMatcher.group(1);

            // Read headers: prefer <thead>, fall back to first <tr> that contains <th> cells
            List<String> headers = new ArrayList<>();
            int hStart = tbl.toLowerCase().indexOf("<thead");
            int hEnd   = tbl.toLowerCase().indexOf("</thead>");
            if (hStart >= 0 && hEnd > hStart) {
                Matcher cellM = TD_PATTERN.matcher(tbl.substring(hStart, hEnd));
                while (cellM.find())
                    headers.add(stripTags(cellM.group(1)).toLowerCase().trim());
            }
            // Fallback: sacnilk header cells use <th scope="col">, data-row Sr.No. uses
            // <th scope="row">.  Matching only scope="col" gives us exactly the header row,
            // even when the header <tr> has no closing </tr> and bleeds into the first data row.
            if (headers.isEmpty()) {
                Matcher thColM = Pattern.compile(
                    "<th[^>]+scope=['\"]col['\"][^>]*>(.*?)</th>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(tbl);
                while (thColM.find())
                    headers.add(stripTags(thColM.group(1)).toLowerCase().trim());
            }

            // Map header positions
            int nameIdx  = colOf(headers, "movie", "film", "title", "name");
            int yearIdx  = colOf(headers, "year", "released", "date");
            int langIdx  = colOf(headers, "language", "lang");
            int dirIdx   = colOf(headers, "director", "directed");
            int genreIdx = colOf(headers, "genre");
            int roleIdx  = colOf(headers, "role", "character", "part", "as");

            // Body rows.
            // sacnilk's header <tr> often lacks a closing </tr>, so TR_PATTERN merges the header
            // cells with the first data row. Splitting on </tr> and detecting the merged case lets
            // us recover the otherwise-lost first movie entry.
            int bStart = tbl.toLowerCase().indexOf("<tbody");
            String body = bStart >= 0 ? tbl.substring(bStart) : tbl;

            String[] rowSegs = body.split("(?i)</tr>");
            boolean firstSeg = true;
            for (String seg : rowSegs) {
                List<String> cells = new ArrayList<>();
                Matcher cellM = TD_PATTERN.matcher(seg);
                while (cellM.find()) cells.add(cellM.group(1));
                if (cells.isEmpty()) { firstSeg = false; continue; }

                // Detect header-merged-with-data: header cells appear as first N cells, data after.
                // Header row in sacnilk has no </tr> so it bleeds into the first data row's segment.
                if (firstSeg && !headers.isEmpty() && cells.size() >= 2 * headers.size()) {
                    boolean startsWithHeader = true;
                    for (int h = 0; h < headers.size(); h++) {
                        if (!stripTags(cells.get(h)).equalsIgnoreCase(headers.get(h))) {
                            startsWithHeader = false; break;
                        }
                    }
                    if (startsWithHeader)
                        cells = new ArrayList<>(cells.subList(headers.size(), cells.size()));
                }
                firstSeg = false;

                // Guess movie-name column: prefer detected header, otherwise col 1 (col 0 = Sr.No.)
                int nIdx = (nameIdx >= 0 && nameIdx < cells.size()) ? nameIdx
                         : (cells.size() > 1 ? 1 : 0);
                String nameCell = cells.get(nIdx);

                // Extract href → movie slug
                String movieSlug = null;
                Matcher hrefM = HREF_PATTERN.matcher(nameCell);
                if (hrefM.find()) {
                    Matcher slugM = MOVIE_SLUG_PAT.matcher(hrefM.group(1));
                    if (slugM.find()) movieSlug = slugM.group(1);
                }

                String movieName = blankToNull(stripTags(nameCell));
                if (movieName == null || movieName.matches("\\d+")
                        || movieName.equalsIgnoreCase("movie name")
                        || movieName.equalsIgnoreCase("film name")) continue;
                // sacnilk sometimes appends the year to the link text (e.g. "Mr.Bond1992"); strip it
                String stripped = movieName.replaceAll("\\s*(\\d{4})$", "").trim();
                if (!stripped.isEmpty()) movieName = stripped;

                // Release date
                String releaseDate = null;
                if (yearIdx >= 0 && yearIdx < cells.size())
                    releaseDate = extractYear(stripTags(cells.get(yearIdx)));
                if (releaseDate == null && movieSlug != null) {
                    Matcher sy = SLUG_YEAR.matcher(movieSlug);
                    if (sy.find()) releaseDate = sy.group(1);
                }
                if (releaseDate == null) {
                    for (String c : cells) {
                        releaseDate = extractYear(stripTags(c));
                        if (releaseDate != null) break;
                    }
                }

                String language  = langIdx  >= 0 && langIdx  < cells.size() ? blankToNull(stripTags(cells.get(langIdx)))  : null;
                String director  = dirIdx   >= 0 && dirIdx   < cells.size() ? blankToNull(stripTags(cells.get(dirIdx)))   : null;
                String genre     = genreIdx >= 0 && genreIdx < cells.size() ? blankToNull(stripTags(cells.get(genreIdx))) : null;
                String role      = roleIdx  >= 0 && roleIdx  < cells.size() ? blankToNull(stripTags(cells.get(roleIdx)))  : null;

                result.add(new ActorMovieEntry(
                    actorName, movieName, releaseDate,
                    language, genre, director, role, movieSlug));
            } // end row loop

            if (!result.isEmpty()) return result; // use first table with data
        }
        return result;
    }

    // ---- HTML list-item parser (fallback) ----

    private List<ActorMovieEntry> parseFromListItems(String actorName, String html) {
        List<ActorMovieEntry> result = new ArrayList<>();
        Pattern liPat = Pattern.compile("<li[^>]*>(.*?)</li>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher lm = liPat.matcher(html);
        while (lm.find()) {
            String item = lm.group(1);
            Matcher hrefM = HREF_PATTERN.matcher(item);
            if (!hrefM.find()) continue;

            String href  = hrefM.group(1);
            String text  = blankToNull(stripTags(item));
            if (text == null) continue;

            String movieSlug = null;
            Matcher slugM = MOVIE_SLUG_PAT.matcher(href);
            if (slugM.find()) movieSlug = slugM.group(1);

            String releaseDate = null;
            if (movieSlug != null) {
                Matcher sy = SLUG_YEAR.matcher(movieSlug);
                if (sy.find()) releaseDate = sy.group(1);
            }
            if (releaseDate == null) releaseDate = extractYear(text);

            result.add(new ActorMovieEntry(
                actorName, text, releaseDate, null, null, null, null, movieSlug));
        }
        return result;
    }

    // ---- helpers ----

    private int colOf(List<String> headers, String... keywords) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            for (String kw : keywords) {
                if (h.contains(kw)) return i;
            }
        }
        return -1;
    }

    private String extractYear(String text) {
        if (text == null) return null;
        Matcher m = YEAR_PAT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static String stripTags(String html) {
        if (html == null) return "";
        return HTML_TAG.matcher(html).replaceAll("")
            .replace("&amp;", "&").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim();
    }

    static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || "-".equals(t) || "n/a".equalsIgnoreCase(t)) ? null : t;
    }

    /** Levenshtein-ratio similarity between two actor name strings (case/punct-insensitive). */
    private static double titleSimilarity(String a, String b) {
        String na = a.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
        String nb = b.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
        if (na.equals(nb)) return 1.0;
        int maxLen = Math.max(na.length(), nb.length());
        if (maxLen == 0) return 1.0;
        if ((double) Math.min(na.length(), nb.length()) / maxLen < 0.3) return 0.0;
        return 1.0 - (double) levenshtein(na, nb) / maxLen;
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
