package com.lit.fire.flame.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches GDP and inflation data from the World Bank API (free, no auth required).
 * Results are cached in memory by (country, year) to avoid redundant calls.
 */
public class WorldBankClient {

    private static final String BASE_URL = "https://api.worldbank.org/v2";
    private static final String GDP_INDICATOR       = "NY.GDP.MKTP.CD";   // GDP in current USD
    private static final String INFLATION_INDICATOR = "FP.CPI.TOTL.ZG";   // Consumer price inflation %

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    public WorldBankClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Returns GDP in billions USD for the country+year, or null if unavailable. */
    public Double fetchGdpBillions(String countryCode, int year) {
        return fetch(countryCode, year, GDP_INDICATOR, 1e9);
    }

    /** Returns annual CPI inflation rate (%) for the country+year, or null if unavailable. */
    public Double fetchInflationRate(String countryCode, int year) {
        return fetch(countryCode, year, INFLATION_INDICATOR, 1.0);
    }

    private Double fetch(String countryCode, int year, String indicator, double divisor) {
        String cacheKey = countryCode + "|" + year + "|" + indicator;
        // ConcurrentHashMap.computeIfAbsent doesn't support null values, so use containsKey guard
        if (cache.containsKey(cacheKey)) return toNullableDouble(cache.get(cacheKey));

        Double value = fetchFromApi(countryCode, year, indicator, divisor);
        // Store sentinel -Double.MAX_VALUE for null so computeIfAbsent-style logic works
        cache.put(cacheKey, value != null ? value : -Double.MAX_VALUE);
        return value;
    }

    private Double fetchFromApi(String countryCode, int year, String indicator, double divisor) {
        String url = String.format("%s/country/%s/indicator/%s?date=%d&format=json",
                BASE_URL, countryCode.toLowerCase(), indicator, year);
        // Retry once — World Bank API can be slow on first contact
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(45))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return null;

                JsonNode root = objectMapper.readTree(response.body());
                if (!root.isArray() || root.size() < 2) return null;
                JsonNode data = root.get(1);
                if (!data.isArray() || data.isEmpty()) return null;

                JsonNode valueNode = data.get(0).get("value");
                if (valueNode == null || valueNode.isNull()) return null;

                double raw = valueNode.asDouble();
                return Math.round(raw / divisor * 100.0) / 100.0;
            } catch (Exception e) {
                lastException = e;
            }
        }
        System.err.printf("  [WorldBank] %s/%d/%s: %s%n", countryCode, year, indicator, lastException.getMessage());
        return null;
    }

    private static Double toNullableDouble(double v) {
        return v == -Double.MAX_VALUE ? null : v;
    }
}
