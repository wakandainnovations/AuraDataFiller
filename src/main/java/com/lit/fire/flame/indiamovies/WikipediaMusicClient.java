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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a film's "Music by" credit from its English Wikipedia infobox — the check
 * IndiaCastCrewFillService runs on every music director it fills. TMDB lists Indian films'
 * composers in no reliable order (the background-score composer often comes first: Andhadhun's
 * first TMDB music credit is Girish Nakod, not Amit Trivedi), while the infobox usually labels
 * "Songs:" and "Score:" separately, and in India the music director is the songs composer.
 *
 * The article is found by exact IMDb id (P345) through its Wikidata item's enwiki sitelink, with
 * the item's publication year (P577) for the caller's year check, since Wikidata carries some
 * wrong IMDb ids (see WikidataCastCrewClient).
 */
public class WikipediaMusicClient {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php";
    /** Wikimedia asks every client to identify itself with a descriptive User-Agent. */
    private static final String USER_AGENT = "AuraDataFiller/1.0 (Indian film data backfill)";
    private static final int SPARQL_BATCH_SIZE = 150;
    /** Pages per content request: Wikipedia rate-limits (429) bigger batches quickly. */
    private static final int PAGE_BATCH_SIZE = 20;
    private static final long PAUSE_BETWEEN_REQUESTS_MS = 3000;
    private static final int MAX_RETRIES = 4;

    /** One name in the infobox's music field; section is "songs", "score", "other" or "" (unlabelled). */
    public record Credit(String section, String name, String linkTarget) {}

    /**
     * A film's infobox music credits, in infobox order.
     *
     * @param year earliest publication-date (P577) year of the Wikidata item, or null
     */
    public record Music(Integer year, List<Credit> credits) {

        /** The songs composers, else the unlabelled names, else every name. */
        public List<Credit> primary() {
            List<Credit> songs = credits.stream().filter(c -> c.section().equals("songs")).toList();
            if (!songs.isEmpty()) return songs;
            List<Credit> unlabelled = credits.stream().filter(c -> c.section().isEmpty()).toList();
            return unlabelled.isEmpty() ? credits : unlabelled;
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IMDb id -> infobox music credits, for every id with an English article that has any. */
    public Map<String, Music> musicByImdbId(Collection<String> imdbIds) throws Exception {
        record Article(String title, Integer year) {}
        Map<String, Article> articles = new HashMap<>();
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
                String title = java.net.URLDecoder.decode(url.substring(url.lastIndexOf('/') + 1), StandardCharsets.UTF_8)
                    .replace('_', ' ');
                Integer year = b.has("year") ? b.path("year").path("value").asInt() : null;
                Article prev = articles.putIfAbsent(id, new Article(title, year));
                if (prev != null && !prev.title().equals(title)) ambiguous.add(id);
            }
            Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS / 3);
        }
        ambiguous.forEach(articles::remove);

        Map<String, String> wikitextByTitle = new HashMap<>();
        List<String> titles = articles.values().stream().map(Article::title).distinct().toList();
        for (int i = 0; i < titles.size(); i += PAGE_BATCH_SIZE) {
            if (i > 0) Thread.sleep(PAUSE_BETWEEN_REQUESTS_MS);
            wikitextByTitle.putAll(wikitext(titles.subList(i, Math.min(i + PAGE_BATCH_SIZE, titles.size()))));
        }

        Map<String, Music> result = new HashMap<>();
        articles.forEach((id, article) -> {
            List<Credit> credits = parseCredits(infoboxParam(wikitextByTitle.get(article.title()), "music"));
            if (!credits.isEmpty()) result.put(id, new Music(article.year(), credits));
        });
        return result;
    }

    // ---- choosing the music director ----

    /** Same person under different names, as the India table and TMDB spell them. */
    private static final List<Set<String>> ALIASES = List.of(
        Set.of("koti", "salurikoteswararao"), Set.of("mmkreem", "mmkeeravaani", "mmkeeravani"),
        Set.of("dsp", "devisriprasad"), Set.of("ilaiyaraaja", "ilayaraja", "ilaiyaraja"),
        Set.of("gvprakash", "gvprakashkumar"), Set.of("thaman", "sthaman", "thamans", "ssthaman"),
        Set.of("meetbros", "meetbrosanjjan", "manmeetsingh", "harmeetsingh", "anjjanbhattacharya"),
        Set.of("adboys", "adboyz"), Set.of("bharathwaj", "bharadwaj"), Set.of("sharibsabri", "toshisabri", "sharibtoshi"));
    private static final Pattern TEAM_SEPARATOR = Pattern.compile("\\s*[–—-]\\s*");
    private static final Pattern NOT_A_NAME = Pattern.compile("(?i)\\b(songs?|score|background|lyrics|music|label|records)\\b");

    /**
     * The music director to store given what the other sources found: {@code current} when it is
     * one of the infobox's songs composers (or, with no songs label, any unlabelled name), else
     * the first of those, spelled as {@code tmdbComposers} or {@code knownSpellings} (normalised
     * name -> spelling) already have it so the name-based movie counts find the person's other
     * films. Returns {@code current} when the infobox gives nothing usable.
     */
    public static String chooseMusicDirector(String current, Music music, List<String> tmdbComposers,
                                             Map<String, String> knownSpellings) {
        List<Credit> primary = music.primary();
        if (primary.isEmpty()) return current;
        if (current != null && !current.isBlank()) {
            if (primary.stream().anyMatch(c -> sameMusician(current, c.name()) || sameMusician(current, c.linkTarget()))) return current;
            if (current.equals("Shankar Ehsan Loy") && primary.stream().anyMatch(c -> c.name().toLowerCase(Locale.ROOT).contains("shankar"))) {
                return current;
            }
        }
        Credit first = primary.get(0);
        // A link target is the fuller name ("Pritam Chakraborty" behind "Pritam").
        String name = first.linkTarget().length() > first.name().length() ? first.linkTarget() : first.name();
        String spelled = spelling(name, tmdbComposers, knownSpellings);
        // Unlinked runs of several names ("Bapi-Tutul Ishq Bector Punnu Brar") can't be split reliably.
        boolean known = !spelled.equals(name) || knownSpellings.containsKey(normalize(name));
        if (!known && (name.split("\\s+").length > 3 || NOT_A_NAME.matcher(name).find())) return current;
        return spelled;
    }

    private static String spelling(String name, List<String> tmdbComposers, Map<String, String> knownSpellings) {
        String n = normalize(name);
        if (n.equals("shankarehsaanloy") || n.equals("shankarehsanloy")) return "Shankar Ehsan Loy";
        for (String composer : tmdbComposers) {
            if (sameMusician(name, composer)) return composer;
        }
        String known = knownSpellings.get(n);
        if (known != null) return known;
        for (Set<String> group : ALIASES) {
            if (group.size() > 3 || !group.contains(n)) continue;
            for (String alias : group) {
                if (knownSpellings.containsKey(alias)) return knownSpellings.get(alias);
            }
        }
        return name;
    }

    /** Whether music credits a and b plausibly name the same person or team. */
    static boolean sameMusician(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb) || similarity(na, nb) >= 0.85) return true;
        if (ALIASES.stream().anyMatch(g -> g.contains(na) && g.contains(nb))) return true;
        List<String> ta = tokens(a), tb = tokens(b);
        // "Pritam" vs "Pritam Chakraborty"
        if (ta.size() == 1 && ta.get(0).length() >= 4 && tb.contains(ta.get(0))) return true;
        if (tb.size() == 1 && tb.get(0).length() >= 4 && ta.contains(tb.get(0))) return true;
        // "Thaman S" vs "S Thaman", "G. V. Prakash" vs "G. V. Prakash Kumar"
        if (Math.min(ta.size(), tb.size()) >= 2 && (ta.containsAll(tb) || tb.containsAll(ta))) return true;
        // team "Vishal–Shekhar" vs member "Shekhar Ravjiani"
        return isTeamMember(a, b) || isTeamMember(b, a);
    }

    private static boolean isTeamMember(String team, String person) {
        String[] parts = TEAM_SEPARATOR.split(team.trim());
        String[] personTokens = person.trim().split("\\s+");
        if (parts.length < 2 || personTokens.length == 0) return false;
        String firstName = normalize(personTokens[0]);
        for (String part : parts) {
            if (!firstName.isEmpty() && normalize(part).equals(firstName)) return true;
        }
        return false;
    }

    private static List<String> tokens(String s) {
        return List.of(s.toLowerCase(Locale.ROOT).replace('.', ' ').trim().split("\\s+"));
    }

    /** Lower-case alphanumerics only, like IndiaMovieCountFillService's name matching. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }

    /** Ratcliff/Obershelp ratio, as Python's difflib.SequenceMatcher computes it. */
    private static double similarity(String a, String b) {
        return 2.0 * matchingChars(a, b) / (a.length() + b.length());
    }

    private static int matchingChars(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int bestLen = 0, bestA = 0, bestB = 0;
        int[][] len = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) != b.charAt(j - 1)) continue;
                len[i][j] = len[i - 1][j - 1] + 1;
                if (len[i][j] > bestLen) { bestLen = len[i][j]; bestA = i - bestLen; bestB = j - bestLen; }
            }
        }
        if (bestLen == 0) return 0;
        return bestLen + matchingChars(a.substring(0, bestA), b.substring(0, bestB))
            + matchingChars(a.substring(bestA + bestLen), b.substring(bestB + bestLen));
    }

    // ---- infobox parsing ----

    private static final Pattern INFOBOX_FILM = Pattern.compile("\\{\\{\\s*Infobox[ _]+film", Pattern.CASE_INSENSITIVE);

    /** Raw wikitext of one top-level param of the page's {{Infobox film}}, or null. */
    static String infoboxParam(String wikitext, String name) {
        if (wikitext == null) return null;
        Matcher m = INFOBOX_FILM.matcher(wikitext);
        if (!m.find()) return null;
        Map<String, String> params = new LinkedHashMap<>();
        StringBuilder segment = new StringBuilder();
        int depth = 1;
        for (int j = m.end(); j < wikitext.length(); j++) {
            String two = wikitext.startsWith("{{", j) || wikitext.startsWith("[[", j) ? "open"
                : wikitext.startsWith("}}", j) || wikitext.startsWith("]]", j) ? "close" : null;
            if ("open".equals(two)) {
                depth++; segment.append(wikitext, j, j + 2); j++; continue;
            }
            if ("close".equals(two)) {
                if (--depth == 0) break;
                segment.append(wikitext, j, j + 2); j++; continue;
            }
            if (wikitext.charAt(j) == '|' && depth == 1) {
                addParam(params, segment); segment.setLength(0); continue;
            }
            segment.append(wikitext.charAt(j));
        }
        addParam(params, segment);
        return params.get(name);
    }

    private static void addParam(Map<String, String> params, CharSequence segment) {
        String s = segment.toString();
        int eq = s.indexOf('=');
        if (eq > 0) params.putIfAbsent(s.substring(0, eq).trim().toLowerCase(Locale.ROOT), s.substring(eq + 1));
    }

    private static final Pattern SONGS = Pattern.compile("(?i)songs?|soundtrack");
    private static final Pattern SCORE = Pattern.compile("(?i)score|bgm|background music");
    private static final Pattern LABEL_ON_LINE = Pattern.compile(
        "(?i)('''|\\*)?\\s*\\b((?:additional |original |background |film )?(?:songs?|score|soundtrack)(?: and (?:background )?score)?)\\s*:");
    private static final Pattern TOKEN = Pattern.compile("\\[\\[[^\\]]*\\]\\]|<br\\s*/?>|\\n|\\{\\{|\\}\\}|\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_TEMPLATE = Pattern.compile(
        "(?i)plain ?list|unbulleted ?list|ubl|flatlist|hlist|indented plainlist|nowrap");
    private static final Pattern FILLER = Pattern.compile(
        "(?i)songs?|score|background|soundtrack|music|original|various artists?|see|see soundtrack|original songs");
    private static final Pattern TRAILING_PARENS = Pattern.compile("\\s*\\(.*?\\)\\s*$");

    /** The names in an infobox music field, with the "Songs:" / "Score:" section each falls under. */
    static List<Credit> parseCredits(String value) {
        List<Credit> credits = new ArrayList<>();
        if (value == null) return credits;
        String v = value.replaceAll("(?s)<ref[^>]*/>|<ref[^>]*>.*?</ref>|<!--.*?-->", "")
            .replaceAll("(?i)\\{\\{\\s*(?:snd|endash|ndash|en dash)\\s*\\}\\}", "–")
            .replaceAll("(?i)</?(?:small|span|div|sup|i|b)[^>]*>", " ")
            .replaceAll("(?i)\\{\\{\\s*(?:efn|refn|sfn|nbsp|ref)[^{}]*\\}\\}", " ");
        v = LABEL_ON_LINE.matcher(v).replaceAll("\n$2:");

        String section = "";
        Matcher t = TOKEN.matcher(v);
        int last = 0;
        List<String> pieces = new ArrayList<>();
        while (t.find()) {
            if (t.start() > last) pieces.add(v.substring(last, t.start()));
            pieces.add(t.group());
            last = t.end();
        }
        if (last < v.length()) pieces.add(v.substring(last));

        for (String piece : pieces) {
            if (piece.isBlank() || piece.matches("(?i)<br\\s*/?>|\\n|\\{\\{|\\}\\}|\\|")) continue;
            if (piece.startsWith("[[")) {
                String[] parts = piece.substring(2, piece.length() - 2).split("\\|");
                String target = parts[0].trim();
                if (target.matches("(?i)(file|image):.*") || target.contains("#") || target.toLowerCase(Locale.ROOT).contains("soundtrack")) {
                    continue;
                }
                String label = TRAILING_PARENS.matcher(parts[parts.length - 1].trim()).replaceAll("");
                credits.add(new Credit(section, label, TRAILING_PARENS.matcher(target).replaceAll("")));
                continue;
            }
            String raw = piece.replaceAll("'''?|\\*", " ").trim();
            if (raw.isEmpty() || LIST_TEMPLATE.matcher(raw).matches()) continue;
            String label = null, rest = raw;
            int colon = raw.indexOf(':');
            if (colon >= 0 && colon < 50) {
                label = raw.substring(0, colon);
                rest = raw.substring(colon + 1);
            } else if (colon < 0 && raw.split("\\s+").length <= 5 && raw.matches("(?i)[a-z &/,-]+")
                && (SONGS.matcher(raw).find() || SCORE.matcher(raw).find())) {
                label = raw;
                rest = "";
            }
            if (label != null) {
                section = SONGS.matcher(label).find() ? "songs" : SCORE.matcher(label).find() ? "score" : "other";
            }
            for (String p : rest.split(",|;|\\band\\b|&")) {
                String name = p.replaceAll("\\(.*?\\)", "").replaceAll("^[\\s.\\-–()]+|[\\s.\\-–()]+$", "");
                if (name.length() >= 3 && !FILLER.matcher(name).matches()) credits.add(new Credit(section, name, name));
            }
        }
        return credits;
    }

    // ---- HTTP ----

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
