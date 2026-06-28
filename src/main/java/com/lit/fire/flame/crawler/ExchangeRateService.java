package com.lit.fire.flame.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches historical INR→USD exchange rates from www.xe.com/currencytables/.
 * Rates are cached per date for the lifetime of this instance.
 *
 * Lookup order for each date:
 *   1. In-memory cache (pre-populated from currency_rate_xe DB table by caller).
 *   2. Live fetch from xe.com currency tables page.
 *
 * After a crawl cycle, the caller should persist all newly fetched rates
 * (those not originally in the DB) to currency_rate_xe via {@link #getNewlyFetchedRates()}.
 */
public class ExchangeRateService {

    private static final String XE_TABLES_URL  = "https://www.xe.com/currencytables/?from=INR&date=";
    private static final long   INR_PER_CRORE  = 10_000_000L;
    private static final String USER_AGENT      =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Matches the USD entry inside the historicRates array embedded in __NEXT_DATA__:
    // {"currency":"USD","rate":0.01311382...}
    private static final Pattern USD_RATE = Pattern.compile(
        "\"currency\"\\s*:\\s*\"USD\"\\s*,\\s*\"rate\"\\s*:\\s*([0-9.]+)"
    );

    private final HttpClient          httpClient;
    private final Map<String, Double> cache         = new HashMap<>();
    private final Set<String>         preloadedDates = new HashSet<>();

    public ExchangeRateService() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Pre-populates the in-memory cache with rates already stored in the DB.
     * These pre-loaded rates are NOT included in {@link #getNewlyFetchedRates()},
     * so they will not be re-persisted to the DB after the crawl cycle.
     */
    public void preloadCache(Map<String, Double> existingRates) {
        cache.putAll(existingRates);
        preloadedDates.addAll(existingRates.keySet());
    }

    /**
     * Returns the INR→USD rate for the given date string (expects YYYY-MM-DD prefix).
     * If the date is in the future or unparseable, falls back to today's rate.
     *
     * @throws IOException          if the xe.com request fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public double getInrToUsdRate(String releaseDate) throws IOException, InterruptedException {
        String queryDate = resolveQueryDate(releaseDate);
        Double cached = cache.get(queryDate);
        if (cached != null) return cached;

        double rate = fetchFromXe(queryDate);
        cache.put(queryDate, rate);
        return rate;
    }

    /**
     * Converts an INR Crore amount to full USD (rounded to the nearest dollar).
     *
     * @param crore        value in Indian Rupees Crore (e.g. 250.0 for ₹250 Cr)
     * @param inrToUsdRate INR→USD rate from {@link #getInrToUsdRate}
     * @return full USD amount as a whole number (e.g. 27,800,000 for ₹250 Cr at 0.01112)
     */
    public long inrCroreToUsd(double crore, double inrToUsdRate) {
        double inr = crore * INR_PER_CRORE;
        return Math.round(inr * inrToUsdRate);
    }

    /** Returns all rates fetched or pre-loaded so far this instance (date → INR→USD rate). */
    public Map<String, Double> getCachedRates() {
        return Collections.unmodifiableMap(cache);
    }

    /**
     * Returns only rates that were freshly fetched from xe.com during this instance's lifetime
     * (i.e., rates NOT pre-loaded from the DB). These are the rates that need to be persisted.
     */
    public Map<String, Double> getNewlyFetchedRates() {
        Map<String, Double> result = new HashMap<>(cache);
        preloadedDates.forEach(result::remove);
        return result;
    }

    // ---- private helpers ----

    private double fetchFromXe(String date) throws IOException, InterruptedException {
        String url = XE_TABLES_URL + date;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .version(HttpClient.Version.HTTP_1_1)
            .header("User-Agent",      USER_AGENT)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer",         "https://www.xe.com/")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                "xe.com returned HTTP " + response.statusCode() + " for date " + date);
        }

        Matcher m = USD_RATE.matcher(response.body());
        if (!m.find()) {
            throw new IOException(
                "Could not find USD rate in xe.com response for date " + date +
                " (body length=" + response.body().length() + ")");
        }

        return Double.parseDouble(m.group(1));
    }

    private String resolveQueryDate(String releaseDate) {
        if (releaseDate != null && releaseDate.length() >= 10) {
            try {
                LocalDate d = LocalDate.parse(releaseDate.substring(0, 10));
                if (!d.isAfter(LocalDate.now())) {
                    return d.toString();
                }
            } catch (DateTimeParseException ignored) {}
        }
        return LocalDate.now().toString();
    }
}
