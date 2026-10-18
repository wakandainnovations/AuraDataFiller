package com.lit.fire.flame.indiamovies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks a film's opening screen count out of its English Wikipedia article text, for
 * IndiaScreensFillService. Articles state it in prose ("released in 131 screens in Kerala and in
 * 116 screens in rest of India") alongside overseas counts, later expansions, other films' records
 * and per-language splits, so every "N screens/theatres/cinemas" mention in a release sentence is
 * labelled with the area it covers, from the words right after it or else the nearest area named
 * before it in the same sentence:
 * <ol>
 *   <li>an all-India figure ("in India", "nationwide", "domestic") wins;</li>
 *   <li>else the home state plus "rest of India" / "outside Kerala" in the same sentence, summed;</li>
 *   <li>else worldwide minus overseas from the same sentence;</li>
 *   <li>else an unqualified figure ("released in 1,200 screens"), from a sentence with at most two counts;</li>
 *   <li>else the home state's figure alone (the state of the film's language), scope "state:&lt;state&gt;";</li>
 *   <li>else a worldwide-only figure, scope "worldwide" — the caller records but does not store it,
 *       since number_of_screens holds India counts (Box Office India's convention).</li>
 * </ol>
 * Theatre counts give way to a screen count given alongside ("2,550 cinemas across 3,550 screens").
 * Prototyped against all 1,338 articles of rows still missing a count on 2026-10-17 and checked by
 * hand; known misses are overseas releases whose sentence names no country.
 */
public class ScreenCountExtractor {

    /** scope: "india", "unspecified", "state:&lt;state&gt;" or "worldwide"; sentence: the evidence. */
    public record Pick(String scope, int screens, String sentence) {}

    private enum Area { INDIA, STATE, REST, WORLD, FOREIGN, CITY, UNSPECIFIED }

    private record Mention(Area area, String state, int n, String sentence, int start, int end, boolean screenUnit, int countsInSentence) {}

    private static final String NUM = "(\\d{1,3}(?:,\\d{3})+|\\d{1,5})";
    private static final Pattern CANDIDATE = Pattern.compile(
        "(?i)(?:over|more than|nearly|around|about|approximately|close to|almost|some|a record|record)?\\s*\\b" + NUM
            + "\\s*\\+?\\s*(?:-\\s*)?(?:screens?|theat(?:re|er)s?|cinemas?|cinema halls)\\b");

    private static final Map<String, String> STATES = new LinkedHashMap<>();
    static {
        STATES.put("kerala", "kerala");
        STATES.put("tamil nadu", "tamil nadu");
        STATES.put("karnataka", "karnataka");
        for (String k : List.of("andhra pradesh", "telangana", "telugu states", "ap and ts", "ap & ts", "nizam")) {
            STATES.put(k, "andhra/telangana");
        }
        STATES.put("west bengal", "west bengal");
        STATES.put("bengal", "west bengal");
        STATES.put("maharashtra", "maharashtra");
        STATES.put("punjab", "punjab");
        STATES.put("gujarat", "gujarat");
        STATES.put("odisha", "odisha");
        STATES.put("assam", "assam");
    }
    private static final Map<String, String> LANGUAGE_STATE = Map.of(
        "malayalam", "kerala", "tamil", "tamil nadu", "kannada", "karnataka", "telugu", "andhra/telangana",
        "bengali", "west bengal", "marathi", "maharashtra", "punjabi", "punjab", "gujarati", "gujarat",
        "odia", "odisha", "assamese", "assam");

    private static final Pattern FOREIGN = Pattern.compile(
        "manhattan|new york|london|times square|emirates|prefecture|japan|rest of the world|overseas|turkey|france|ireland"
            + "|netherlands|fiji|kenya|south africa|nepal|indonesia|korea|russia|taiwan|hong kong|qatar|oman|kuwait|bahrain"
            + "|saudi|dubai|europe|america|international|united states|\\bus\\b|u\\.s\\.|usa|north america|canada"
            + "|united kingdom|\\buk\\b|britain|australia|new zealand|uae|gulf|gcc|middle east|pakistan|bangladesh|malaysia"
            + "|singapore|sri lanka|china|germany|countries|markets|abroad|outside india|foreign");
    private static final Pattern WORLD = Pattern.compile(
        "across the globe|the globe|worldwide|world-wide|globally|across the world|around the world|all over the world");
    private static final Pattern INDIA = Pattern.compile(
        "\\bin india\\b|across india|all over india|throughout india|from india|indian market|pan-india|pan india");
    private static final Pattern NATIONAL = Pattern.compile(
        "domestic|nationwide|across the country|all over the country|in the country");
    private static final Pattern REST = Pattern.compile(
        "outside (?!india)\\w+|rest of india|other states|other parts of india|rest of the country");
    private static final Pattern CITY = Pattern.compile(
        "\\bnizam\\b|\\barea\\b|ceded|\\bregion|chennai|hyderabad|mumbai|bangalore|bengaluru|kochi|cochin|delhi|kolkata"
            + "|pune|vizag|thiruvananthapuram|trivandrum|coimbatore|madurai|mysore|mangalore");
    private static final Pattern THE_STATE = Pattern.compile("across the state|in the state|statewide");
    private static final Pattern OVERSEAS = Pattern.compile("overseas|international|outside india");

    private static final Pattern RELEASE = Pattern.compile("releas|opened|open in|open on|screened|hit\\b|premiere|distribut");
    /** Sentences about other films' records, later runs or partial counts. */
    private static final Pattern SKIP_SENTENCE = Pattern.compile(
        "broken by|record broken|behind|additional|idols|first (?:two|three|four|five) days|\\d+ days|ran for|still"
            + "|by the end|record of|records set|beaten|surpass|previous|breaking|respectively|than the|compared|equalled|equaled");
    private static final Pattern SKIP_AFTER = Pattern.compile(
        "second day|third day|second week|third week|following week|50 days|100 days|days run|day run");
    private static final Pattern SKIP_BEFORE = Pattern.compile(
        "(?:increased|expanded|raised|went up|rose|extended)(?: to)?(?: over| more than| around| about| nearly)?\\s*$|\\ball\\s*$|'s\\s*$");
    private static final Pattern VERSION = Pattern.compile("version|dubb|\\b3d\\b");
    private static final Pattern LANGUAGE_SPLIT = Pattern.compile(
        "\\s*(?:in|for) (?:the )?(?:telugu|tamil|hindi|malayalam|kannada|bengali|marathi)\\b");
    private static final Pattern CLAUSE_BREAK = Pattern.compile(
        "[,;]| and (?!telangana|ts\\b)| with | while | but | including ", Pattern.CASE_INSENSITIVE);

    /** Wikitext reduced to prose: refs, comments and footnote templates dropped, links to their labels. */
    public static String clean(String wikitext) {
        String w = wikitext.replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("(?s)<ref[^>/]*/>|<ref[^>]*>.*?</ref>", "");
        for (int i = 0; i < 3; i++) {
            w = w.replaceAll("\\{\\{(?:[Ss]fn|[Ee]fn|[Cc]itation needed|[Cc]n|[Ww]hen|[Bb]etter source)[^{}]*\\}\\}", "");
        }
        return w.replaceAll("\\{\\{(?:[Nn]owrap|[Nn]obr)\\|([^{}]*)\\}\\}", "$1")
            .replaceAll("\\{\\{(?:INR|[Ii]ndian [Rr]upee)\\}\\}", "₹")
            .replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*)\\]\\]", "$1")
            .replaceAll("'''?", "")
            .replace("&nbsp;", " ");
    }

    /** The screen count to use for a film in {@code language} (the India table's comma-separated value). */
    public static Optional<Pick> pick(String language, String text) {
        Set<String> home = new LinkedHashSet<>();
        for (String l : (language == null ? "" : language).split(",")) {
            String s = LANGUAGE_STATE.get(l.trim().toLowerCase(Locale.ROOT));
            if (s != null) home.add(s);
        }
        List<Mention> found = mentions(text);
        if (found.isEmpty()) return Optional.empty();

        for (Mention m : found) {
            if (m.area() == Area.INDIA) return Optional.of(new Pick("india", preferScreens(m, found), m.sentence()));
        }
        for (Mention m : found) {
            if (m.area() == Area.STATE && isHome(m, home)) {
                for (Mention r : found) {
                    if (r.area() == Area.REST && r.sentence().equals(m.sentence())) {
                        return Optional.of(new Pick("india", m.n() + r.n(), m.sentence()));
                    }
                }
            }
        }
        for (Mention m : found) {
            if (m.area() != Area.WORLD) continue;
            for (Mention o : found) {
                if (o.area() == Area.FOREIGN && o.sentence().equals(m.sentence())
                        && OVERSEAS.matcher(o.sentence().toLowerCase(Locale.ROOT)).find() && m.n() > o.n()) {
                    return Optional.of(new Pick("india", m.n() - o.n(), m.sentence()));
                }
            }
        }
        for (Mention m : found) {
            if (m.area() == Area.UNSPECIFIED && m.countsInSentence() <= 2) {
                return Optional.of(new Pick("unspecified", preferScreens(m, found), m.sentence()));
            }
        }
        for (Mention m : found) {
            if (m.area() == Area.STATE && isHome(m, home)) {
                String state = m.state().equals("home") ? home.stream().findFirst().orElse("home") : m.state();
                return Optional.of(new Pick("state:" + state, m.n(), m.sentence()));
            }
        }
        for (Mention m : found) {
            if (m.area() == Area.WORLD) return Optional.of(new Pick("worldwide", m.n(), m.sentence()));
        }
        return Optional.empty();
    }

    private static boolean isHome(Mention m, Set<String> home) {
        return m.state() != null && (m.state().equals("home") || home.contains(m.state()));
    }

    /** "2,550 cinemas across 3,550 screens" / "1300 screens in 700 theatres": the screen count. */
    private static int preferScreens(Mention m, List<Mention> found) {
        if (m.screenUnit()) return m.n();
        for (Mention g : found) {
            if (g.sentence().equals(m.sentence()) && g.screenUnit() && g.start() - m.end() >= 0 && g.start() - m.end() <= 40
                    && g.n() > m.n() && (g.area() == m.area() || g.area() == Area.UNSPECIFIED || g.area() == Area.INDIA)) {
                return g.n();
            }
        }
        for (Mention g : found) {
            if (g.sentence().equals(m.sentence()) && g.screenUnit() && m.start() - g.end() >= 0 && m.start() - g.end() <= 15
                    && g.n() > m.n()) {
                return g.n();
            }
        }
        return m.n();
    }

    private static List<Mention> mentions(String text) {
        List<Mention> found = new ArrayList<>();
        String prose = text.replaceAll("(?m)^[|!{].*$", ""); // tables and infobox lines
        for (String raw : prose.split("(?<=[.!?])\\s+(?=[A-Z])|\\n+")) {
            String s = raw.strip();
            String sl = s.toLowerCase(Locale.ROOT);
            if (!RELEASE.matcher(sl).find() || SKIP_SENTENCE.matcher(sl).find()) continue;
            int counts = 0;
            for (Matcher c = CANDIDATE.matcher(s); c.find(); ) counts++;
            int prevEnd = 0;
            Matcher m = CANDIDATE.matcher(s);
            while (m.find()) {
                int pe = prevEnd;
                prevEnd = m.end();
                int n = Integer.parseInt(m.group(1).replace(",", ""));
                if (n < 5 || n > 12000) continue;
                if (SKIP_AFTER.matcher(window(sl, m.end(), 45)).find()) continue;
                if (SKIP_BEFORE.matcher(sl.substring(Math.max(0, m.start() - 30), m.start())).find()) continue;
                if (VERSION.matcher(sl.substring(pe, m.start()) + " " + window(sl, m.end(), 30)).find()) continue;
                if (LANGUAGE_SPLIT.matcher(window(sl, m.end(), 20)).lookingAt()) continue;
                String[] labelled = classify(s, m, pe);
                Area area = Area.valueOf(labelled[0]);
                String state = labelled[1];
                if (area == Area.UNSPECIFIED && NATIONAL.matcher(sl.substring(pe, m.start())).find()) {
                    area = FOREIGN.matcher(window(sl, pe, m.end() - pe + 60)).find() ? Area.FOREIGN : Area.INDIA;
                }
                found.add(new Mention(area, state, n, s, m.start(), m.end(),
                    m.group().toLowerCase(Locale.ROOT).contains("screen"), counts));
            }
        }
        return found;
    }

    private static String window(String s, int from, int len) {
        return s.substring(Math.min(from, s.length()), Math.min(s.length(), from + len));
    }

    /** {area, state}: the area named right after the count, else the last one named before it. */
    private static String[] classify(String sentence, Matcher m, int prevEnd) {
        Matcher next = CANDIDATE.matcher(sentence);
        int nextStart = next.find(m.end()) ? next.start() : sentence.length();
        String after = sentence.substring(m.end(), nextStart);
        after = after.substring(0, Math.min(45, after.length()));
        after = CLAUSE_BREAK.split(after, 2)[0].toLowerCase(Locale.ROOT);
        String sl = sentence.toLowerCase(Locale.ROOT);

        if (THE_STATE.matcher(after).find()) {
            String named = null;
            for (Map.Entry<String, String> e : STATES.entrySet()) {
                if (Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(sl).find()) named = e.getValue();
            }
            return new String[] {"STATE", named == null ? "home" : named};
        }
        Matcher national = NATIONAL.matcher(after);
        if (national.find() && national.start() <= 25) {
            String span = sl.substring(prevEnd, nextStart);
            return new String[] {FOREIGN.matcher(span).find() ? "FOREIGN" : "INDIA", null};
        }
        List<Object[]> a = areas(after);
        if (!a.isEmpty()) return new String[] {(String) a.get(0)[1], (String) a.get(0)[2]};
        List<Object[]> b = areas(sentence.substring(prevEnd, m.start()).toLowerCase(Locale.ROOT));
        if (!b.isEmpty()) return new String[] {(String) b.get(b.size() - 1)[1], (String) b.get(b.size() - 1)[2]};
        return new String[] {"UNSPECIFIED", null};
    }

    /** Area words in t as {start, area, state}, sorted by position. */
    private static List<Object[]> areas(String t) {
        List<Object[]> hits = new ArrayList<>();
        List<int[]> rests = new ArrayList<>();
        addHits(hits, t, FOREIGN, "FOREIGN");
        addHits(hits, t, WORLD, "WORLD");
        for (Matcher r = REST.matcher(t); r.find(); ) {
            hits.add(new Object[] {r.start(), "REST", null});
            rests.add(new int[] {r.start(), r.end()});
        }
        addHits(hits, t, INDIA, "INDIA");
        addHits(hits, t, CITY, "CITY");
        for (Map.Entry<String, String> e : STATES.entrySet()) {
            for (Matcher s = Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(t); s.find(); ) {
                int at = s.start();
                // "outside Kerala" is the rest of India, not Kerala
                if (rests.stream().noneMatch(r -> r[0] <= at && at < r[1])) hits.add(new Object[] {at, "STATE", e.getValue()});
            }
        }
        if (hits.stream().anyMatch(h -> h[1].equals("CITY"))) {
            hits.removeIf(h -> !h[1].equals("CITY") && !h[1].equals("FOREIGN") && !h[1].equals("WORLD"));
        }
        hits.sort((x, y) -> Integer.compare((int) x[0], (int) y[0]));
        return hits;
    }

    private static void addHits(List<Object[]> hits, String t, Pattern p, String area) {
        for (Matcher m = p.matcher(t); m.find(); ) hits.add(new Object[] {m.start(), area, null});
    }
}
