package com.lit.fire.flame.indiamovies;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads boxofficeindia.com film pages (movie.php?movieid=N) for IndiaScreensFillService. Each page
 * carries the all-India opening screen count ("Screens: 5000") for Hindi releases, and Hindi-dubbed
 * versions of the biggest South films. 0 means not recorded (most films from late 2023 on); the
 * site stopped adding films around May 2024 (no id above 6260 as of 2026-10-17).
 *
 * search.php is ajax-driven and ignores query parameters, so films are found by walking ids; the
 * per-year "Top Nett Grossers" lists give the current highest id. robots.txt disallows only /boi-new.
 */
public class BoxOfficeIndiaClient {

    private static final String BASE = "https://boxofficeindia.com/";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final int MAX_RETRIES = 4;
    private static final DateTimeFormatter RELEASE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Pattern MOVIE_ID = Pattern.compile("movieid=(\\d+)");

    /** screens is null when the page records none (blank or 0). */
    public record Movie(int id, String title, LocalDate releaseDate, Integer screens) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** The film with this id, or empty when the id has no film. */
    public Optional<Movie> movie(int id) throws Exception {
        return parse(id, get(BASE + "movie.php?movieid=" + id));
    }

    /** Highest film id linked from the per-year top grossers lists of the given years. */
    public int highestListedId(int fromYear, int toYear, long pauseMs) throws Exception {
        TreeSet<Integer> ids = new TreeSet<>();
        for (int year = fromYear; year <= toYear; year++) {
            Matcher m = MOVIE_ID.matcher(get(BASE + "boi_pages.php?year=" + year + "&pageId=4&more=full"));
            while (m.find()) ids.add(Integer.parseInt(m.group(1)));
            Thread.sleep(pauseMs);
        }
        return ids.isEmpty() ? 0 : ids.last();
    }

    static Optional<Movie> parse(int id, String html) {
        Document doc = Jsoup.parse(html);
        String title = doc.title().replaceFirst("\\s*-\\s*Movie\\s*-\\s*Box Office India\\s*$", "").strip();
        Element dateLabel = doc.getElementsContainingOwnText("Release Date:").first();
        if (title.isEmpty() || dateLabel == null) return Optional.empty();
        Element date = dateLabel.selectFirst("span.redtext");
        if (date == null) return Optional.empty();
        LocalDate releaseDate;
        try {
            releaseDate = LocalDate.parse(date.text().replace(' ', ' ').strip(), RELEASE_DATE);
        } catch (Exception e) {
            return Optional.empty();
        }
        Integer screens = null;
        Element link = doc.selectFirst("a[href=screens.php]");
        Element row = link == null ? null : link.closest("tr");
        if (row != null) {
            String value = row.select("td").last().text().replaceAll("[^0-9]", "");
            if (!value.isEmpty() && Integer.parseInt(value) > 0) screens = Integer.parseInt(value);
        }
        return Optional.of(new Movie(id, title, releaseDate, screens));
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)
            .GET().build();
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return response.body();
                if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                    Thread.sleep(30_000L * (attempt + 1));
                    continue;
                }
                throw new Exception("Box Office India HTTP " + status + " for " + url);
            } catch (java.io.IOException e) {
                if (attempt >= MAX_RETRIES) throw e;
                Thread.sleep(30_000L * (attempt + 1));
            }
        }
    }
}
