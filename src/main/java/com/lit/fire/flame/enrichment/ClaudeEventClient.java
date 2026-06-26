package com.lit.fire.flame.enrichment;

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
 * Detects significant events on a given date in a given country using the Claude Haiku API.
 * Results are cached by (date, country) so multiple movies releasing on the same day share
 * a single API call.
 *
 * Returns [event_type, event_name, event_detail] where event_type is one of:
 *   "National Holiday", "Festival", "Sporting Event", "Political Event", "None"
 */
public class ClaudeEventClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    // Fast and cheap; sufficient for factual event lookups
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private static final String PROMPT_TEMPLATE =
            "What is the single most significant event (if any) that occurred on %s in %s or globally " +
            "that would be relevant to movie box-office performance? " +
            "Check for: " +
            "(1) National holidays in %s; " +
            "(2) Major cultural festivals — Diwali, Eid ul-Fitr, Eid ul-Adha, Christmas, Holi, Pongal, Onam, " +
            "    Navratri, Durga Puja, Ugadi, Vishu, Baisakhi, Lohri, Ganesh Chaturthi, Janmashtami; " +
            "(3) Major sporting events — ICC Cricket World Cup, ICC T20 World Cup, IPL finals, Asia Cup, " +
            "    FIFA World Cup, UEFA Champions League Final, Wimbledon Final, Formula 1 Grand Prix, " +
            "    Olympics opening/closing, Commonwealth Games; " +
            "(4) Political events — Indian General Elections (Lok Sabha), Karnataka state elections, " +
            "    Tamil Nadu state elections, US Presidential elections, UK General elections, " +
            "    any major state or national elections; " +
            "(5) Major world events — summit meetings, disasters, pandemics, etc. " +
            "Respond ONLY with valid JSON and nothing else: " +
            "{\"event_type\":\"National Holiday|Festival|Sporting Event|Political Event|None\"," +
            "\"event_name\":\"name of event or null\"," +
            "\"event_detail\":\"brief extra detail or null\"}";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String[]> cache = new ConcurrentHashMap<>();
    private final long delayMs;

    public ClaudeEventClient(String apiKey, long delayMs) {
        this.apiKey = apiKey;
        this.delayMs = delayMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Returns [event_type, event_name, event_detail] for the given date and country.
     * Cached by (releaseDate, countryName) to avoid repeated API calls.
     */
    public String[] fetchEventInfo(String releaseDate, String countryName) {
        String key = releaseDate + "|" + countryName;
        return cache.computeIfAbsent(key, k -> callClaude(releaseDate, countryName));
    }

    private String[] callClaude(String releaseDate, String countryName) {
        String prompt = String.format(PROMPT_TEMPLATE, releaseDate, countryName, countryName);
        try {
            throttle();
            Map<String, Object> body = new HashMap<>();
            body.put("model", MODEL);
            body.put("max_tokens", 256);
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
                System.err.printf("  [Claude] HTTP %d for %s/%s%n",
                        response.statusCode(), releaseDate, countryName);
                return nullEvent();
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String text = responseJson.path("content").get(0).path("text").asText("");
            return parseEventJson(text);

        } catch (Exception e) {
            System.err.printf("  [Claude] %s/%s: %s%n", releaseDate, countryName, e.getMessage());
            return nullEvent();
        }
    }

    private String[] parseEventJson(String text) {
        try {
            String json = stripMarkdownFences(text.trim());
            JsonNode node = objectMapper.readTree(json);
            String type   = node.path("event_type").asText("None");
            String name   = node.path("event_name").isNull()   ? null : node.path("event_name").asText();
            String detail = node.path("event_detail").isNull() ? null : node.path("event_detail").asText();
            // Normalise "null" string values
            if ("null".equalsIgnoreCase(name))   name   = null;
            if ("null".equalsIgnoreCase(detail)) detail = null;
            return new String[]{type, name, detail};
        } catch (Exception e) {
            System.err.println("  [Claude] Could not parse event JSON: " + text);
            return nullEvent();
        }
    }

    private static String stripMarkdownFences(String text) {
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0) return text;
            int closing = text.lastIndexOf("```");
            if (closing > newline) return text.substring(newline + 1, closing).trim();
        }
        return text;
    }

    private static String[] nullEvent() {
        return new String[]{"None", null, null};
    }

    private void throttle() {
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
