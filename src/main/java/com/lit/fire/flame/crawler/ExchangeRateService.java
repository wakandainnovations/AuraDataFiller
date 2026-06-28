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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches historical INR→USD exchange rates from api.frankfurter.app (free, no API key).
 * Rates are cached per date for the lifetime of this instance.
 *
 * Frankfurter uses ECB data and returns the rate for the closest preceding business day
 * when the exact date falls on a weekend or public holiday.
 */
public class ExchangeRateService {

    private static final String API_BASE    = "https://api.frankfurter.app/";
    private static final long   INR_PER_CRORE = 10_000_000L;
    private static final Pattern RATE_PATTERN =
        Pattern.compile("\"USD\"\\s*:\\s*([0-9.]+)");

    private final HttpClient httpClient;
    private final Map<String, Double> cache = new HashMap<>();

    public ExchangeRateService() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Returns the INR→USD rate for the given date string (expects YYYY-MM-DD prefix).
     * If the date is in the future or unparseable, falls back to today's rate.
     *
     * @throws IOException          if the API request fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public double getInrToUsdRate(String releaseDate) throws IOException, InterruptedException {
        String queryDate = resolveQueryDate(releaseDate);
        Double cached = cache.get(queryDate);
        if (cached != null) return cached;

        String url = API_BASE + queryDate + "?from=INR&to=USD";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                "Frankfurter API returned HTTP " + response.statusCode() +
                " for date " + queryDate);
        }

        Matcher m = RATE_PATTERN.matcher(response.body());
        if (!m.find()) {
            throw new IOException(
                "Could not parse USD rate from Frankfurter response: " + response.body());
        }

        double rate = Double.parseDouble(m.group(1));
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

    /** Returns all rates fetched so far this instance (date → INR→USD rate). */
    public Map<String, Double> getCachedRates() {
        return Collections.unmodifiableMap(cache);
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
