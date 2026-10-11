package com.lit.fire.flame.directors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads English Wikipedia's yearly per-language film lists ("List of Hindi films of 2015",
 * "List of Tamil films of 2012", ...) — the most complete public listing of Indian films with
 * their directors, including small regional releases missing from TMDB and Wikidata.
 *
 * Pages are fetched through the MediaWiki API (rendered HTML) and every "wikitable" with both a
 * title column and a director column is read; other tables on the page (box-office rankings,
 * awards) are ignored. Tables use rowspan/colspan heavily (e.g. a month or release-date cell
 * spanning many rows), so each table is expanded into a full grid first so every row's cells
 * line up with the header. Pages are cached per (language, year) for the run, with a pause
 * between fetches to stay polite to Wikipedia.
 */
public class WikipediaFilmListClient {

    private static final String API = "https://en.wikipedia.org/w/api.php";
    /** Wikimedia asks every client to identify itself with a descriptive User-Agent. */
    private static final String USER_AGENT = "AuraDataFiller/1.0 (Indian film data backfill)";
    private static final long PAUSE_BETWEEN_FETCHES_MS = 1500;
    private static final int MAX_RETRIES = 4;

    private static final Pattern TITLE_HEADER = Pattern.compile("^(title|film|movie|name)s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTOR_HEADER = Pattern.compile("^direct(or|ors|or\\(s\\)|ed by)\\b", Pattern.CASE_INSENSITIVE);

    /** One film row: its title and director(s) ("A, B") as listed. */
    public record FilmEntry(String title, String directors) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<FilmEntry>> cache = new HashMap<>();
    private long lastFetchAtMs = 0;

    /**
     * Every film with a director on "List of {language} films of {year}", or an empty list when
     * Wikipedia has no such page.
     *
     * @param language Wikipedia's language name as used in the page title, e.g. "Hindi"
     */
    public List<FilmEntry> films(String language, int year) throws Exception {
        String page = "List of " + language + " films of " + year;
        List<FilmEntry> cached = cache.get(page);
        if (cached != null) return cached;
        List<FilmEntry> films = parse(fetchHtml(page));
        cache.put(page, films);
        return films;
    }

    private String fetchHtml(String page) throws Exception {
        String url = API + "?action=parse&prop=text&format=json&formatversion=2&redirects=1&page=" +
            URLEncoder.encode(page.replace(' ', '_'), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)
            .GET().build();
        HttpResponse<String> response;
        for (int attempt = 0; ; attempt++) {
            long wait = lastFetchAtMs + PAUSE_BETWEEN_FETCHES_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastFetchAtMs = System.currentTimeMillis();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                // Honor Retry-After when given, else back off 10s, 20s, 40s...
                long waitSec = response.headers().firstValueAsLong("Retry-After").orElse(10L << attempt);
                Thread.sleep(waitSec * 1000);
                continue;
            }
            break;
        }
        if (response.statusCode() != 200) {
            throw new Exception("Wikipedia HTTP " + response.statusCode() + " for '" + page + "'");
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.has("error")) {
            if ("missingtitle".equals(root.path("error").path("code").asText())) return null;
            throw new Exception("Wikipedia API error for '" + page + "': " + root.path("error").path("info").asText());
        }
        return root.path("parse").path("text").asText(null);
    }

    static List<FilmEntry> parse(String html) {
        List<FilmEntry> films = new ArrayList<>();
        if (html == null) return films;
        Document doc = Jsoup.parse(html);
        doc.select("sup.reference, sup.noprint, style, .sortkey").remove();
        // A line break inside a cell separates names ("A<br>B") — keep that as a list separator.
        doc.select("br").forEach(br -> br.replaceWith(new org.jsoup.nodes.TextNode(", ")));

        for (Element table : doc.select("table.wikitable")) {
            List<Boolean> isHeaderRow = new ArrayList<>();
            List<List<String>> grid = expand(table, isHeaderRow);
            int titleCol = -1, directorCol = -1, headerRow = -1;
            // Headers may span two rows (grouped column headings); only header-cell rows count, so
            // a data cell like "Best Music Director" in an awards table is never taken for one.
            for (int r = 0; r < grid.size() && isHeaderRow.get(r) && (titleCol < 0 || directorCol < 0); r++) {
                List<String> row = grid.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String h = row.get(c);
                    if (titleCol < 0 && TITLE_HEADER.matcher(h).find()) titleCol = c;
                    if (directorCol < 0 && DIRECTOR_HEADER.matcher(h).find()) directorCol = c;
                }
                headerRow = r;
            }
            if (titleCol < 0 || directorCol < 0) continue;

            for (int r = headerRow + 1; r < grid.size(); r++) {
                List<String> row = grid.get(r);
                if (row.size() <= Math.max(titleCol, directorCol)) continue;
                String title = row.get(titleCol);
                String directors = cleanNames(row.get(directorCol));
                if (!title.isEmpty() && directors != null && !title.equals(directors)) {
                    films.add(new FilmEntry(title, directors));
                }
            }
        }
        return films;
    }

    /** "A , B,  C" -> "A, B, C"; null for empty or placeholder cells. */
    private static String cleanNames(String cell) {
        List<String> names = new ArrayList<>();
        for (String part : cell.split("\\s*,\\s*|\\s+&\\s+|\\s+and\\s+")) {
            String name = part.trim();
            if (!name.isEmpty() && !name.equals("—") && !name.equals("-") && !names.contains(name)) names.add(name);
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    /**
     * The table's cell texts as a rectangular grid, with every rowspan/colspan cell copied into
     * each slot it covers. {@code isHeaderRow} receives, per row, whether all its own cells are
     * header (th) cells.
     */
    private static List<List<String>> expand(Element table, List<Boolean> isHeaderRow) {
        List<List<String>> grid = new ArrayList<>();
        Map<Integer, Map<Integer, String>> carried = new HashMap<>();  // row -> col -> text from a rowspan above
        int r = 0;
        for (Element tr : table.select("> tbody > tr, > thead > tr, > tr")) {
            List<String> row = new ArrayList<>();
            isHeaderRow.add(!tr.select("> th").isEmpty() && tr.select("> td").isEmpty());
            Map<Integer, String> fromAbove = carried.getOrDefault(r, Map.of());
            int c = 0;
            for (Element cell : tr.select("> th, > td")) {
                while (fromAbove.containsKey(c)) row.add(fromAbove.get(c++));
                String text = cell.text().replace(' ', ' ').trim();
                int colspan = span(cell.attr("colspan"));
                int rowspan = span(cell.attr("rowspan"));
                for (int k = 0; k < colspan; k++, c++) {
                    row.add(text);
                    for (int dr = 1; dr < rowspan; dr++) {
                        carried.computeIfAbsent(r + dr, x -> new HashMap<>()).put(c, text);
                    }
                }
            }
            while (fromAbove.containsKey(c)) row.add(fromAbove.get(c++));
            grid.add(row);
            r++;
        }
        return grid;
    }

    private static int span(String attr) {
        try {
            int n = Integer.parseInt(attr.trim());
            return n > 0 ? Math.min(n, 500) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
