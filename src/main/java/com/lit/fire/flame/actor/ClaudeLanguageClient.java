package com.lit.fire.flame.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the primary language of a movie via the Claude Haiku API, for sources
 * (kulfiy, fandango) that don't expose a language field. Results are cached by
 * (movieName, releaseYear) so repeated lookups across actors sharing a movie are free.
 *
 * Returns "Unknown" if the API call fails or Claude cannot confidently determine
 * the language.
 */
public class ClaudeLanguageClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private static final String PROMPT_TEMPLATE =
        "What is the primary spoken language of the movie \"%s\"%s? " +
        "Answer with just the language name (e.g. \"Tamil\", \"Telugu\", \"Hindi\", \"Malayalam\", " +
        "\"Kannada\", \"English\"), or \"Unknown\" if you cannot determine it with confidence. " +
        "Respond with only the language name and nothing else.";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final long delayMs;

    public ClaudeLanguageClient(String apiKey, long delayMs) {
        this.apiKey = apiKey;
        this.delayMs = delayMs;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** Returns the resolved language name, or "Unknown" if it cannot be determined. */
    public String fetchLanguage(String movieName, String releaseYear) {
        String key = movieName.trim().toLowerCase() + "|" + (releaseYear == null ? "" : releaseYear);
        return cache.computeIfAbsent(key, k -> callClaude(movieName, releaseYear));
    }

    private String callClaude(String movieName, String releaseYear) {
        String yearSuffix = (releaseYear != null && !releaseYear.isBlank())
            ? " (released " + releaseYear + ")" : "";
        String prompt = String.format(PROMPT_TEMPLATE, movieName, yearSuffix);
        try {
            throttle();
            Map<String, Object> body = new HashMap<>();
            body.put("model", MODEL);
            body.put("max_tokens", 32);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.printf("  [ClaudeLanguage] HTTP %d for %s%n", response.statusCode(), movieName);
                return "Unknown";
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String text = responseJson.path("content").get(0).path("text").asText("").trim();
            return normalize(text);

        } catch (Exception e) {
            System.err.printf("  [ClaudeLanguage] %s: %s%n", movieName, e.getMessage());
            return "Unknown";
        }
    }

    private static String normalize(String text) {
        if (text.isEmpty()) return "Unknown";
        // Strip trailing punctuation and quotes Claude sometimes wraps the answer in.
        String cleaned = text.replaceAll("^[\"'\\s]+|[\"'\\s.]+$", "");
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("unknown")
                || cleaned.length() > 40) { // sanity bound — a real answer is a short language name
            return "Unknown";
        }
        return cleaned;
    }

    private void throttle() {
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
