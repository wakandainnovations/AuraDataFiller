package com.lit.fire.flame.indiamovies;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Looks up a film's cast (P161), directors (P57) and composers (P86) on Wikidata by exact IMDb id
 * (P345) — the fallback source for IndiaCastCrewFillService when TMDB has nothing. Wikidata
 * sometimes carries a wrong IMDb id (e.g. tt1869542, the 2010 Hindi "London Calling", is also on a
 * Bruce Springsteen concert film), so each result includes the film's year for the caller to check.
 *
 * Cast order matters (it decides who the leads are) and SPARQL results are unordered, so the
 * film's entity JSON (wbgetentities) is read instead: its statements keep the order editors
 * entered them in, which for films follows the credits. A "series ordinal" (P1545) qualifier,
 * where present, takes precedence. An IMDb id that resolves to several Wikidata items is skipped.
 */
public class WikidataCastCrewClient {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String API_ENDPOINT = "https://www.wikidata.org/w/api.php";
    /** Wikimedia asks every client to identify itself with a descriptive User-Agent. */
    private static final String USER_AGENT = "AuraDataFiller/1.0 (Indian film data backfill)";
    private static final int SPARQL_BATCH_SIZE = 200;
    /** wbgetentities accepts at most 50 ids per request. */
    private static final int ENTITY_BATCH_SIZE = 50;
    private static final long PAUSE_BETWEEN_REQUESTS_MS = 500;
    private static final int MAX_RETRIES = 3;

    /**
     * One film's credits, each list in credit order (empty when Wikidata has none).
     *
     * @param year earliest publication-date (P577) year, or null when Wikidata has none
     */
    public record Credits(Integer year, List<String> cast, List<String> directors, List<String> composers) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IMDb id -> credits, for every id that resolves to exactly one Wikidata item. */
    public Map<String, Credits> creditsByImdbId(Collection<String> imdbIds) throws Exception {
        Map<String, String> qidByImdb = qidsByImdbId(imdbIds);

        // Film entities -> ordered person QIDs per property.
        Map<String, Map<String, List<String>>> peopleByQid = new HashMap<>();
        Map<String, Integer> yearByQid = new HashMap<>();
        List<String> filmQids = new ArrayList<>(new LinkedHashSet<>(qidByImdb.values()));
        for (int i = 0; i < filmQids.size(); i += ENTITY_BATCH_SIZE) {
            JsonNode entities = getEntities(filmQids.subList(i, Math.min(i + ENTITY_BATCH_SIZE, filmQids.size())), "claims");
            entities.fields().forEachRemaining(e -> {
                JsonNode claims = e.getValue().path("claims");
                Integer year = earliestYear(claims.path("P577"));
                if (year != null) yearByQid.put(e.getKey(), year);
                peopleByQid.put(e.getKey(), Map.of(
                    "P161", orderedPeople(claims.path("P161")),
                    "P57", orderedPeople(claims.path("P57")),
                    "P86", orderedPeople(claims.path("P86"))));
            });
        }

        // Person QIDs -> English labels.
        Set<String> personQids = new LinkedHashSet<>();
        peopleByQid.values().forEach(m -> m.values().forEach(personQids::addAll));
        Map<String, String> labels = new HashMap<>();
        List<String> people = new ArrayList<>(personQids);
        for (int i = 0; i < people.size(); i += ENTITY_BATCH_SIZE) {
            JsonNode entities = getEntities(people.subList(i, Math.min(i + ENTITY_BATCH_SIZE, people.size())), "labels");
            entities.fields().forEachRemaining(e -> {
                String label = e.getValue().path("labels").path("en").path("value").asText("").trim();
                if (!label.isEmpty()) labels.put(e.getKey(), label);
            });
        }

        Map<String, Credits> result = new HashMap<>();
        qidByImdb.forEach((imdb, qid) -> {
            Map<String, List<String>> p = peopleByQid.get(qid);
            if (p == null) return;
            result.put(imdb, new Credits(yearByQid.get(qid), names(p.get("P161"), labels), names(p.get("P57"), labels), names(p.get("P86"), labels)));
        });
        return result;
    }

    private static List<String> names(List<String> qids, Map<String, String> labels) {
        return qids.stream().map(labels::get).filter(l -> l != null).distinct().toList();
    }

    /** Earliest year among a film's publication-date (P577) statements ("+2010-02-05T00:00:00Z"). */
    private static Integer earliestYear(JsonNode claimList) {
        Integer earliest = null;
        for (JsonNode claim : claimList) {
            String time = claim.path("mainsnak").path("datavalue").path("value").path("time").asText("");
            if (!time.matches("^\\+\\d{4}-.*")) continue;
            int year = Integer.parseInt(time.substring(1, 5));
            if (earliest == null || year < earliest) earliest = year;
        }
        return earliest;
    }

    /** Person QIDs of a claim list, in statement order, re-sorted by any P1545 ordinal (stable). */
    private static List<String> orderedPeople(JsonNode claimList) {
        record Ranked(String qid, int ordinal, int position) {}
        List<Ranked> ranked = new ArrayList<>();
        int position = 0;
        for (JsonNode claim : claimList) {
            if ("deprecated".equals(claim.path("rank").asText())) continue;
            String qid = claim.path("mainsnak").path("datavalue").path("value").path("id").asText("");
            if (qid.isEmpty()) continue;
            int ordinal = Integer.MAX_VALUE;
            JsonNode ord = claim.path("qualifiers").path("P1545");
            if (ord.isArray() && !ord.isEmpty()) {
                try {
                    ordinal = Integer.parseInt(ord.get(0).path("datavalue").path("value").asText().trim());
                } catch (NumberFormatException ignored) {
                    // non-numeric ordinal — keep statement order
                }
            }
            ranked.add(new Ranked(qid, ordinal, position++));
        }
        ranked.sort(Comparator.comparingInt(Ranked::ordinal).thenComparingInt(Ranked::position));
        return ranked.stream().map(Ranked::qid).distinct().toList();
    }

    private Map<String, String> qidsByImdbId(Collection<String> imdbIds) throws Exception {
        List<String> all = new ArrayList<>(new HashSet<>(imdbIds));
        Map<String, String> result = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int i = 0; i < all.size(); i += SPARQL_BATCH_SIZE) {
            if (i > 0) Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS);
            String values = all.subList(i, Math.min(i + SPARQL_BATCH_SIZE, all.size())).stream()
                .map(id -> "\"" + id.replace("\\", "").replace("\"", "") + "\"")
                .collect(Collectors.joining(" "));
            String sparql = "SELECT ?id ?film WHERE { VALUES ?id { " + values + " } ?film wdt:P345 ?id. }";
            for (JsonNode b : sparql(sparql).path("results").path("bindings")) {
                String id = b.path("id").path("value").asText();
                String uri = b.path("film").path("value").asText();
                String qid = uri.substring(uri.lastIndexOf('/') + 1);
                String prev = result.putIfAbsent(id, qid);
                if (prev != null && !prev.equals(qid)) ambiguous.add(id);
            }
        }
        ambiguous.forEach(result::remove);
        return result;
    }

    private JsonNode getEntities(List<String> qids, String props) throws Exception {
        Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS);
        String url = API_ENDPOINT + "?action=wbgetentities&format=json&props=" + props
            + (props.equals("labels") ? "&languages=en" : "")
            + "&ids=" + URLEncoder.encode(String.join("|", qids), StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(URI.create(url)).GET()).path("entities");
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
