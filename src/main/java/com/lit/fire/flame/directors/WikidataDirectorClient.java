package com.lit.fire.flame.directors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Looks up film directors (Wikidata property P57) on Wikidata's public SPARQL endpoint by an
 * exact external id — IMDb id (P345) or TMDB movie id (P4947) — so there's no title/year
 * guesswork: a film either carries that id on Wikidata or isn't found. An id that resolves to
 * more than one Wikidata film (a data error on Wikidata's side) is skipped rather than guessed.
 *
 * Ids are sent in batches (one SPARQL query per batch) with a pause between queries, well within
 * Wikidata's query-service usage limits for a one-off job. No API key is needed.
 */
public class WikidataDirectorClient {

    private static final String ENDPOINT = "https://query.wikidata.org/sparql";
    /** Wikimedia asks every client to identify itself with a descriptive User-Agent. */
    private static final String USER_AGENT = "AuraDataFiller/1.0 (Indian film data backfill)";
    private static final int BATCH_SIZE = 200;
    private static final long PAUSE_BETWEEN_QUERIES_MS = 1000;
    private static final int MAX_RETRIES = 3;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IMDb id ("tt0461200") -> directors ("A, B"), for every id Wikidata has a director for. */
    public Map<String, String> directorsByImdbId(Collection<String> imdbIds) throws Exception {
        return directorsById("P345", imdbIds);
    }

    /** TMDB movie id -> directors ("A, B"), for every id Wikidata has a director for. */
    public Map<Integer, String> directorsByTmdbId(Collection<Integer> tmdbIds) throws Exception {
        List<String> ids = tmdbIds.stream().map(String::valueOf).toList();
        return directorsById("P4947", ids).entrySet().stream()
            .collect(Collectors.toMap(e -> Integer.valueOf(e.getKey()), Map.Entry::getValue));
    }

    private Map<String, String> directorsById(String idProperty, Collection<String> ids) throws Exception {
        List<String> all = new ArrayList<>(new HashSet<>(ids));
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < all.size(); i += BATCH_SIZE) {
            if (i > 0) Thread.sleep(PAUSE_BETWEEN_QUERIES_MS);
            result.putAll(queryBatch(idProperty, all.subList(i, Math.min(i + BATCH_SIZE, all.size()))));
        }
        return result;
    }

    private Map<String, String> queryBatch(String idProperty, List<String> ids) throws Exception {
        String values = ids.stream()
            .map(id -> "\"" + id.replace("\\", "").replace("\"", "") + "\"")
            .collect(Collectors.joining(" "));
        String sparql =
            "SELECT ?id ?film (GROUP_CONCAT(DISTINCT ?dirLabel; separator=\", \") AS ?directors) WHERE { " +
            "VALUES ?id { " + values + " } " +
            "?film wdt:" + idProperty + " ?id; wdt:P57 ?dir. " +
            "?dir rdfs:label ?dirLabel FILTER(lang(?dirLabel) = \"en\") " +
            "} GROUP BY ?id ?film";

        JsonNode bindings = post(sparql).path("results").path("bindings");
        Map<String, String> directors = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (JsonNode b : bindings) {
            String id = b.path("id").path("value").asText();
            String names = b.path("directors").path("value").asText("").trim();
            if (names.isEmpty()) continue;
            if (directors.putIfAbsent(id, names) != null) ambiguous.add(id);
        }
        ambiguous.forEach(directors::remove);
        return directors;
    }

    private JsonNode post(String sparql) throws Exception {
        String body = "query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                long waitSec = response.headers().firstValueAsLong("Retry-After").orElse(5L * (attempt + 1));
                Thread.sleep(waitSec * 1000);
                continue;
            }
            if (status != 200) {
                String text = response.body();
                throw new Exception("Wikidata HTTP " + status + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
            }
            return objectMapper.readTree(response.body());
        }
    }
}
