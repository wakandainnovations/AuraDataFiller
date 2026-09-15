package com.lit.fire.flame.kannadabudget;

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
 * Fetches and parses the single mtwikiblog.com article listing Kannada-territory box office
 * collection/budget/verdict tables, grouped into year sections (2026, 2023, 2022 as of the
 * current article revision). The page mixes in pan-India films tracked only for their
 * Karnataka-territory ("India Kannada Net") collection — this parser returns every row
 * unfiltered; deciding what counts as an actual Kannada movie vs. an existing non-Kannada DB
 * row is KannadaBudgetBackfillService's job, not this parser's.
 *
 * robots.txt (checked 2026-09-13) disallows only "/search" — this article path is unaffected.
 */
public class KannadaBudgetPageParser {

    static final String PAGE_URL =
        "https://www.mtwikiblog.com/2018/10/kannada-box-office-collection-budget-verdict-hit-or-flop.html";
    private static final String SITE_BASE = "https://www.mtwikiblog.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Years this article currently sections its tables by; only these have a known exchange rate. */
    private static final int[] KNOWN_YEARS = {2022, 2023, 2026};

    private static final Pattern HEADING = Pattern.compile("<h[1-4][^>]*>(.*?)</h[1-4]>", Pattern.DOTALL);
    private static final Pattern TABLE   = Pattern.compile("<table.*?</table>", Pattern.DOTALL);
    private static final Pattern ROW     = Pattern.compile("<tr.*?</tr>", Pattern.DOTALL);
    private static final Pattern CELL    = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
    private static final Pattern TAG     = Pattern.compile("<[^>]+>");
    private static final Pattern NUMBER  = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    /**
     * Returns true when the article path is allowed for the wildcard (*) user-agent per
     * robots.txt. Returns true (assume allowed) when robots.txt cannot be fetched.
     */
    public boolean isPathAllowed(String path) {
        try {
            String robotsTxt = fetch(SITE_BASE + "/robots.txt", SITE_BASE + "/");
            return !isDisallowedByWildcard(robotsTxt, path);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isDisallowedByWildcard(String robotsTxt, String path) {
        boolean inWildcard = false;
        for (String line : robotsTxt.split("\n")) {
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.toLowerCase().startsWith("user-agent:")) {
                inWildcard = line.substring("user-agent:".length()).trim().equals("*");
            } else if (inWildcard && line.toLowerCase().startsWith("disallow:")) {
                String disallowed = line.substring("disallow:".length()).trim();
                if (!disallowed.isEmpty() && path.startsWith(disallowed)) return true;
            }
        }
        return false;
    }

    /** One row from a year-sectioned box-office table. budgetCr/revenueCr are null when unparseable. */
    public record Row(String movieName, int year, Double revenueCr, Double budgetCr) {}

    /**
     * Fetches the article and parses every table into Rows, using the nearest preceding
     * heading to determine which of KNOWN_YEARS a table belongs to. A table whose heading
     * doesn't mention any of KNOWN_YEARS is skipped entirely (no exchange rate to convert with).
     */
    public List<Row> fetchAndParse() throws IOException, InterruptedException {
        String html = fetch(PAGE_URL, SITE_BASE + "/");

        List<int[]> headingSpans = new ArrayList<>(); // {startIndex}
        List<String> headingTexts = new ArrayList<>();
        Matcher h = HEADING.matcher(html);
        while (h.find()) {
            headingSpans.add(new int[]{h.start()});
            headingTexts.add(stripTags(h.group(1)));
        }

        List<Row> rows = new ArrayList<>();
        Matcher t = TABLE.matcher(html);
        while (t.find()) {
            int tableStart = t.start();
            Integer year = nearestPrecedingYear(headingSpans, headingTexts, tableStart);
            if (year == null) continue;

            String tableHtml = t.group();
            Matcher r = ROW.matcher(tableHtml);
            while (r.find()) {
                List<String> cells = new ArrayList<>();
                Matcher c = CELL.matcher(r.group());
                while (c.find()) cells.add(stripTags(c.group(1)));
                if (cells.size() < 4) continue;

                String movieName = cells.get(0);
                if (movieName.isEmpty() || movieName.equalsIgnoreCase("Movie Name")) continue;

                Double revenueCr = parseCroreValue(cells.get(1));
                Double budgetCr  = parseCroreValue(cells.get(3));
                rows.add(new Row(movieName, year, revenueCr, budgetCr));
            }
        }
        return rows;
    }

    private Integer nearestPrecedingYear(List<int[]> headingSpans, List<String> headingTexts, int tableStart) {
        String lastHeading = null;
        for (int i = 0; i < headingSpans.size(); i++) {
            if (headingSpans.get(i)[0] < tableStart) {
                lastHeading = headingTexts.get(i);
            } else {
                break;
            }
        }
        if (lastHeading == null) return null;
        for (int year : KNOWN_YEARS) {
            if (lastHeading.contains(String.valueOf(year))) return year;
        }
        return null;
    }

    /**
     * Parses cell text like "340 Crore", "0 Crore", "-- Crore", "10-12 Crore" (range → average),
     * "-" (no data) into a Crore value, or null when no number is present.
     */
    static Double parseCroreValue(String cellText) {
        if (cellText == null) return null;
        Matcher m = NUMBER.matcher(cellText);
        List<Double> nums = new ArrayList<>();
        while (m.find()) {
            try { nums.add(Double.parseDouble(m.group())); } catch (NumberFormatException ignored) {}
        }
        if (nums.isEmpty()) return null;
        if (nums.size() == 1) return nums.get(0);
        return (nums.get(0) + nums.get(1)) / 2.0;
    }

    private static String stripTags(String html) {
        String noTags = TAG.matcher(html).replaceAll("");
        return decodeEntities(noTags).trim();
    }

    private static String decodeEntities(String s) {
        return s.replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

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

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }
}
