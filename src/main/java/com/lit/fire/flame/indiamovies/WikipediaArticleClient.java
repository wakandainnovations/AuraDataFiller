package com.lit.fire.flame.indiamovies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
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
 * Fetches films' English Wikipedia wikitext by IMDb id, for IndiaScreensFillService: the article is
 * found through the Wikidata item holding that IMDb id (P345) and its enwiki sitelink, as
 * WikipediaMusicClient does, with the item's earliest publication year (P577) so the caller can
 * reject Wikidata's occasional wrong IMDb ids. Ids mapped to more than one article are dropped.
 */
public class WikipediaArticleClient {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php";
    /** Wikimedia asks every client to identify itself with a descriptive User-Agent. */
    private static final String USER_AGENT = "AuraDataFiller/1.0 (Indian film data backfill)";
    private static final int SPARQL_BATCH_SIZE = 150;
    /** Pages per content request: Wikipedia rate-limits (429) bigger batches quickly. */
    private static final int PAGE_BATCH_SIZE = 20;
    private static final long PAUSE_BETWEEN_REQUESTS_MS = 3000;
    private static final int MAX_RETRIES = 4;

    /** year: earliest publication-date (P577) year of the Wikidata item, or null. */
    public record Article(String title, Integer year, String wikitext) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IMDb id -> article, for every id with exactly one English article. */
    public Map<String, Article> articlesByImdbId(Collection<String> imdbIds) throws Exception {
        record Link(String title, Integer year) {}
        Map<String, Link> links = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        List<String> all = new ArrayList<>(new HashSet<>(imdbIds));
        for (int i = 0; i < all.size(); i += SPARQL_BATCH_SIZE) {
            String values = all.subList(i, Math.min(i + SPARQL_BATCH_SIZE, all.size())).stream()
                .map(id -> "\"" + id.replace("\\", "").replace("\"", "") + "\"")
                .collect(Collectors.joining(" "));
            String sparql = "SELECT ?id ?article (MIN(YEAR(?date)) AS ?year) WHERE { VALUES ?id { " + values + " }"
                + " ?film wdt:P345 ?id. ?article schema:about ?film; schema:isPartOf <https://en.wikipedia.org/>."
                + " OPTIONAL { ?film wdt:P577 ?date } } GROUP BY ?id ?article";
            for (JsonNode b : sparql(sparql).path("results").path("bindings")) {
                String id = b.path("id").path("value").asText();
                String url = b.path("article").path("value").asText();
                String title = URLDecoder.decode(url.substring(url.lastIndexOf('/') + 1), StandardCharsets.UTF_8).replace('_', ' ');
                Integer year = b.has("year") ? b.path("year").path("value").asInt() : null;
                Link prev = links.putIfAbsent(id, new Link(title, year));
                if (prev != null && !prev.title().equals(title)) ambiguous.add(id);
            }
            Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS / 3);
        }
        ambiguous.forEach(links::remove);

        Map<String, String> wikitextByTitle = new HashMap<>();
        List<String> titles = links.values().stream().map(Link::title).distinct().toList();
        for (int i = 0; i < titles.size(); i += PAGE_BATCH_SIZE) {
            if (i > 0) Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS);
            wikitextByTitle.putAll(wikitext(titles.subList(i, Math.min(i + PAGE_BATCH_SIZE, titles.size()))));
        }

        Map<String, Article> result = new HashMap<>();
        links.forEach((id, link) -> {
            String text = wikitextByTitle.get(link.title());
            if (text != null) result.put(id, new Article(link.title(), link.year(), text));
        });
        return result;
    }

    private Map<String, String> wikitext(List<String> titles) throws Exception {
        String url = WIKIPEDIA_API + "?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2"
            + "&redirects=1&titles=" + URLEncoder.encode(String.join("|", titles), StandardCharsets.UTF_8);
        JsonNode query = send(HttpRequest.newBuilder(URI.create(url)).GET()).path("query");
        Map<String, String> renamed = new HashMap<>();
        for (String key : List.of("normalized", "redirects")) {
            for (JsonNode r : query.path(key)) renamed.put(r.path("from").asText(), r.path("to").asText());
        }
        Map<String, String> byPage = new HashMap<>();
        for (JsonNode page : query.path("pages")) {
            JsonNode content = page.path("revisions").path(0).path("slots").path("main").path("content");
            if (!content.isMissingNode()) byPage.put(page.path("title").asText(), content.asText());
        }
        Map<String, String> result = new HashMap<>();
        for (String title : titles) {
            String resolved = title;
            for (int hop = 0; hop < 3 && renamed.containsKey(resolved); hop++) resolved = renamed.get(resolved);
            String text = byPage.get(resolved);
            if (text != null) result.put(title, text);
        }
        return result;
    }

    private JsonNode sparql(String query) throws Exception {
        String body = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(URI.create(SPARQL_ENDPOINT))
            .header("Accept", "application/sparql-results+json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private JsonNode send(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder.timeout(Duration.ofSeconds(90)).header("User-Agent", USER_AGENT).build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                long waitSec = response.headers().firstValueAsLong("Retry-After").orElse(30L * (attempt + 1));
                Thread.sleep(waitSec * 1000);
                continue;
            }
            if (status != 200) {
                String text = response.body();
                throw new Exception("Wikipedia/Wikidata HTTP " + status + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
            }
            return objectMapper.readTree(response.body());
        }
    }
}
