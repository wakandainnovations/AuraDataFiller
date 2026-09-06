package com.lit.fire.flame.releaseevent;

import com.lit.fire.flame.enrichment.CountryResolver;
import com.lit.fire.flame.marketing.AuraLlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Backfills "release_event_type" / "release_event_name" / "release_event_detail" (see
 * ColumnMapper) for existing Indian-language movies_data_collection rows that carry a full
 * YYYY-MM-DD release_date, most-recently-released first, via AuraLLM: for each row's release
 * date, country (resolved from its language, see CountryResolver), and — for languages with a
 * clear home state (Tamil -> Tamil Nadu, Kannada -> Karnataka, Malayalam -> Kerala, etc., see
 * {@link #indianStateFor}) — that state, asks whether a Holiday, Festival, Election (national
 * or that state's), Cricket World Cup, or Football World Cup match fell within 7 days of
 * release, else classifies it "Normal".
 *
 * Unlike EnrichmentService's ClaudeEventClient (wired into new-row CSV import only, calls the
 * Anthropic API directly, and uses a different event taxonomy), this reaches rows already
 * sitting in the table and goes through AuraLLM — the shared local chat gateway this app's
 * other backfills use (see MarketingTacticsService) — instead of a paid hosted API.
 *
 * Results are cached per (release_date, country, state) within a single cycle: movies sharing
 * the exact release date, country, AND state (a common case — many Indian films in the same
 * language open on the same Friday) reuse one AuraLLM call instead of issuing one per movie.
 * State is part of the key (not just country) so a Tamil and a Kannada movie releasing on the
 * same day — both resolving to India — aren't wrongly given each other's state-election result.
 *
 * Thread model mirrors CreditsCrawlerService/MarketingTacticsService: run() loops forever with
 * a configurable interval (default 24h); runOnce() does a single cycle for one-shot/manual
 * invocation (--release-event-scan-once). runRecheckNormalOnce() re-examines every row already
 * classified "Normal" — plus every already-released row still unclassified (NULL/blank), so it
 * doesn't have to wait its turn in the incremental main backlog — against the current (larger)
 * taxonomy, for --release-event-recheck-normal, since a row classified before Cricket/Football
 * World Cup or state-election awareness existed may no longer be a true "Normal".
 */
public class ReleaseEventService implements Runnable {

    private static final String PREFIX = "[RELEASE-EVENT] ";
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Home state for languages with one clear match — used to ask AuraLLM about that specific
     * state's Legislative Assembly elections, in addition to national elections. Languages
     * without a single obvious state (Hindi, Urdu, Sanskrit — spoken/official across many
     * states) are intentionally omitted; national-election coverage still applies to them.
     */
    private static final Map<String, String> INDIAN_STATE_BY_LANGUAGE = Map.ofEntries(
        Map.entry("tamil",      "Tamil Nadu"),
        Map.entry("kannada",    "Karnataka"),
        Map.entry("tulu",       "Karnataka"),
        Map.entry("malayalam",  "Kerala"),
        Map.entry("telugu",     "Andhra Pradesh or Telangana"),
        Map.entry("marathi",    "Maharashtra"),
        Map.entry("bengali",    "West Bengal"),
        Map.entry("punjabi",    "Punjab"),
        Map.entry("gujarati",   "Gujarat"),
        Map.entry("odia",       "Odisha"),
        Map.entry("oriya",      "Odisha"),
        Map.entry("assamese",   "Assam"),
        Map.entry("bhojpuri",   "Bihar"),
        Map.entry("rajasthani", "Rajasthan"),
        Map.entry("rajastani",  "Rajasthan"),
        Map.entry("konkani",    "Goa"),
        Map.entry("kashmiri",   "Jammu and Kashmir")
    );

    /** Returns the Indian state associated with a language, or null when there isn't one clear match. */
    private static String indianStateFor(String language) {
        if (language == null) return null;
        return INDIAN_STATE_BY_LANGUAGE.get(language.trim().toLowerCase());
    }

    /** A tournament/election's real date span, used as ground truth instead of trusting the LLM's recall of dates. */
    private record DateWindow(LocalDate start, LocalDate end, String name) {
        boolean overlaps(LocalDate a, LocalDate b) {
            return !start.isAfter(b) && !end.isBefore(a);
        }
    }

    /**
     * Real Legislative Assembly election windows (first phase of polling to result/counting
     * day) for the states in {@link #INDIAN_STATE_BY_LANGUAGE}, keyed by individual state name
     * (a value like "Andhra Pradesh or Telangana" is split into its parts when checked — see
     * {@link #findStateElectionOverlap}). Hardcoded for the same reason as
     * {@link #CRICKET_WORLD_CUPS}: a test run had AuraLLM attach "Kerala Assembly Election" /
     * "Tamil Nadu Assembly Election" / "Maharashtra Legislative Assembly Elections" to March-
     * May 2017 releases — those states' real elections were held in 2016 and 2021 (5-year
     * terms), not 2017 — a full year off, the same failure mode as the World Cup mix-up.
     */
    private static final Map<String, List<DateWindow>> STATE_ELECTIONS = Map.ofEntries(
        Map.entry("Tamil Nadu", List.of(
            new DateWindow(LocalDate.of(2011, 4, 13), LocalDate.of(2011, 5, 13), "2011 Tamil Nadu Assembly Election"),
            new DateWindow(LocalDate.of(2016, 5, 16), LocalDate.of(2016, 5, 19), "2016 Tamil Nadu Assembly Election"),
            new DateWindow(LocalDate.of(2021, 4, 6),  LocalDate.of(2021, 5, 2),  "2021 Tamil Nadu Assembly Election"))),
        Map.entry("Karnataka", List.of(
            new DateWindow(LocalDate.of(2013, 5, 5),  LocalDate.of(2013, 5, 8),  "2013 Karnataka Assembly Election"),
            new DateWindow(LocalDate.of(2018, 5, 12), LocalDate.of(2018, 5, 15), "2018 Karnataka Assembly Election"),
            new DateWindow(LocalDate.of(2023, 5, 10), LocalDate.of(2023, 5, 13), "2023 Karnataka Assembly Election"))),
        Map.entry("Kerala", List.of(
            new DateWindow(LocalDate.of(2011, 4, 13), LocalDate.of(2011, 5, 13), "2011 Kerala Assembly Election"),
            new DateWindow(LocalDate.of(2016, 5, 16), LocalDate.of(2016, 5, 19), "2016 Kerala Assembly Election"),
            new DateWindow(LocalDate.of(2021, 4, 6),  LocalDate.of(2021, 5, 2),  "2021 Kerala Assembly Election"))),
        Map.entry("Maharashtra", List.of(
            new DateWindow(LocalDate.of(2014, 10, 15), LocalDate.of(2014, 10, 19), "2014 Maharashtra Assembly Election"),
            new DateWindow(LocalDate.of(2019, 10, 21), LocalDate.of(2019, 10, 24), "2019 Maharashtra Assembly Election"),
            new DateWindow(LocalDate.of(2024, 11, 20), LocalDate.of(2024, 11, 23), "2024 Maharashtra Assembly Election"))),
        Map.entry("West Bengal", List.of(
            new DateWindow(LocalDate.of(2011, 4, 18), LocalDate.of(2011, 5, 13), "2011 West Bengal Assembly Election"),
            new DateWindow(LocalDate.of(2016, 4, 4),  LocalDate.of(2016, 5, 19), "2016 West Bengal Assembly Election"),
            new DateWindow(LocalDate.of(2021, 3, 27), LocalDate.of(2021, 5, 2),  "2021 West Bengal Assembly Election"))),
        Map.entry("Punjab", List.of(
            new DateWindow(LocalDate.of(2012, 1, 30), LocalDate.of(2012, 3, 6),  "2012 Punjab Assembly Election"),
            new DateWindow(LocalDate.of(2017, 2, 4),  LocalDate.of(2017, 3, 11), "2017 Punjab Assembly Election"),
            new DateWindow(LocalDate.of(2022, 2, 20), LocalDate.of(2022, 3, 10), "2022 Punjab Assembly Election"))),
        Map.entry("Gujarat", List.of(
            new DateWindow(LocalDate.of(2012, 12, 13), LocalDate.of(2012, 12, 20), "2012 Gujarat Assembly Election"),
            new DateWindow(LocalDate.of(2017, 12, 9),  LocalDate.of(2017, 12, 18), "2017 Gujarat Assembly Election"),
            new DateWindow(LocalDate.of(2022, 12, 1),  LocalDate.of(2022, 12, 8),  "2022 Gujarat Assembly Election"))),
        Map.entry("Odisha", List.of(
            new DateWindow(LocalDate.of(2014, 4, 10), LocalDate.of(2014, 5, 16), "2014 Odisha Assembly Election"),
            new DateWindow(LocalDate.of(2019, 4, 11), LocalDate.of(2019, 5, 23), "2019 Odisha Assembly Election"),
            new DateWindow(LocalDate.of(2024, 5, 13), LocalDate.of(2024, 6, 4),  "2024 Odisha Assembly Election"))),
        Map.entry("Assam", List.of(
            new DateWindow(LocalDate.of(2011, 4, 4),  LocalDate.of(2011, 5, 13), "2011 Assam Assembly Election"),
            new DateWindow(LocalDate.of(2016, 4, 4),  LocalDate.of(2016, 5, 19), "2016 Assam Assembly Election"),
            new DateWindow(LocalDate.of(2021, 3, 27), LocalDate.of(2021, 5, 2),  "2021 Assam Assembly Election"))),
        Map.entry("Bihar", List.of(
            new DateWindow(LocalDate.of(2015, 10, 12), LocalDate.of(2015, 11, 8),  "2015 Bihar Assembly Election"),
            new DateWindow(LocalDate.of(2020, 10, 28), LocalDate.of(2020, 11, 10), "2020 Bihar Assembly Election"),
            new DateWindow(LocalDate.of(2025, 10, 28), LocalDate.of(2025, 11, 14), "2025 Bihar Assembly Election"))),
        Map.entry("Rajasthan", List.of(
            new DateWindow(LocalDate.of(2013, 12, 1),  LocalDate.of(2013, 12, 8),  "2013 Rajasthan Assembly Election"),
            new DateWindow(LocalDate.of(2018, 12, 7),  LocalDate.of(2018, 12, 11), "2018 Rajasthan Assembly Election"),
            new DateWindow(LocalDate.of(2023, 11, 25), LocalDate.of(2023, 12, 3),  "2023 Rajasthan Assembly Election"))),
        Map.entry("Goa", List.of(
            new DateWindow(LocalDate.of(2012, 3, 3),  LocalDate.of(2012, 3, 6),  "2012 Goa Assembly Election"),
            new DateWindow(LocalDate.of(2017, 2, 4),  LocalDate.of(2017, 3, 11), "2017 Goa Assembly Election"),
            new DateWindow(LocalDate.of(2022, 2, 14), LocalDate.of(2022, 3, 10), "2022 Goa Assembly Election"))),
        Map.entry("Jammu and Kashmir", List.of(
            new DateWindow(LocalDate.of(2014, 11, 25), LocalDate.of(2014, 12, 23), "2014 Jammu and Kashmir Assembly Election"),
            new DateWindow(LocalDate.of(2024, 9, 18),  LocalDate.of(2024, 10, 8),  "2024 Jammu and Kashmir Assembly Election"))),
        Map.entry("Andhra Pradesh", List.of(
            new DateWindow(LocalDate.of(2014, 4, 30), LocalDate.of(2014, 5, 16), "2014 Andhra Pradesh Assembly Election"),
            new DateWindow(LocalDate.of(2019, 4, 11), LocalDate.of(2019, 5, 23), "2019 Andhra Pradesh Assembly Election"),
            new DateWindow(LocalDate.of(2024, 5, 13), LocalDate.of(2024, 6, 4),  "2024 Andhra Pradesh Assembly Election"))),
        Map.entry("Telangana", List.of(
            new DateWindow(LocalDate.of(2014, 4, 30), LocalDate.of(2014, 5, 16), "2014 Telangana Assembly Election"),
            new DateWindow(LocalDate.of(2018, 12, 7), LocalDate.of(2018, 12, 11), "2018 Telangana Assembly Election"),
            new DateWindow(LocalDate.of(2023, 11, 30), LocalDate.of(2023, 12, 3), "2023 Telangana Assembly Election")))
    );

    /**
     * Checks {@code stateDisplay} (which may be a single state or "X or Y") against
     * {@link #STATE_ELECTIONS}, returning the first matching real election's name whose window
     * overlaps [a, b], or null if none of the state(s) has one.
     */
    private static String findStateElectionOverlap(String stateDisplay, LocalDate a, LocalDate b) {
        if (stateDisplay == null) return null;
        for (String state : stateDisplay.split(" or ")) {
            String overlap = findOverlap(STATE_ELECTIONS.getOrDefault(state.trim(), List.of()), a, b);
            if (overlap != null) return overlap;
        }
        return null;
    }

    /**
     * Real (men's, senior) ICC Cricket World Cup and T20 World Cup date ranges. Hardcoded
     * rather than left to the LLM's recall: a test run asked AuraLLM to classify "Football
     * World Cup" for a June-2017 release — there was no FIFA World Cup that year (it likely
     * confused it with the 2017 Confederations Cup) — so tournament dates are now ground truth
     * checked in Java, and {@link #validateWorldCupClassification} rejects any LLM response
     * claiming a World Cup category outside these windows rather than writing it to the DB.
     */
    private static final List<DateWindow> CRICKET_WORLD_CUPS = List.of(
        new DateWindow(LocalDate.of(2003, 2, 9),  LocalDate.of(2003, 3, 23), "2003 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2007, 3, 13), LocalDate.of(2007, 4, 28), "2007 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2007, 9, 11), LocalDate.of(2007, 9, 24), "2007 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2009, 6, 5),  LocalDate.of(2009, 6, 21), "2009 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2010, 4, 30), LocalDate.of(2010, 5, 16), "2010 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2011, 2, 19), LocalDate.of(2011, 4, 2),  "2011 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2012, 9, 18), LocalDate.of(2012, 10, 7), "2012 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2014, 3, 16), LocalDate.of(2014, 4, 6),  "2014 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2015, 2, 14), LocalDate.of(2015, 3, 29), "2015 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2016, 3, 8),  LocalDate.of(2016, 4, 3),  "2016 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2019, 5, 30), LocalDate.of(2019, 7, 14), "2019 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2021, 10, 17), LocalDate.of(2021, 11, 14), "2021 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2022, 10, 16), LocalDate.of(2022, 11, 13), "2022 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2023, 10, 5), LocalDate.of(2023, 11, 19), "2023 ICC Cricket World Cup"),
        new DateWindow(LocalDate.of(2024, 6, 1),  LocalDate.of(2024, 6, 29), "2024 ICC T20 World Cup"),
        new DateWindow(LocalDate.of(2026, 2, 7),  LocalDate.of(2026, 3, 8),  "2026 ICC T20 World Cup")
    );

    /** Real FIFA (men's, senior) World Cup date ranges — see {@link #CRICKET_WORLD_CUPS} javadoc. */
    private static final List<DateWindow> FOOTBALL_WORLD_CUPS = List.of(
        new DateWindow(LocalDate.of(2002, 5, 31), LocalDate.of(2002, 6, 30), "2002 FIFA World Cup"),
        new DateWindow(LocalDate.of(2006, 6, 9),  LocalDate.of(2006, 7, 9),  "2006 FIFA World Cup"),
        new DateWindow(LocalDate.of(2010, 6, 11), LocalDate.of(2010, 7, 11), "2010 FIFA World Cup"),
        new DateWindow(LocalDate.of(2014, 6, 12), LocalDate.of(2014, 7, 13), "2014 FIFA World Cup"),
        new DateWindow(LocalDate.of(2018, 6, 14), LocalDate.of(2018, 7, 15), "2018 FIFA World Cup"),
        new DateWindow(LocalDate.of(2022, 11, 20), LocalDate.of(2022, 12, 18), "2022 FIFA World Cup"),
        new DateWindow(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 7, 19), "2026 FIFA World Cup")
    );

    /** Returns the overlapping tournament's name, or null when none of {@code windows} overlaps [a, b]. */
    private static String findOverlap(List<DateWindow> windows, LocalDate a, LocalDate b) {
        for (DateWindow w : windows) {
            if (w.overlaps(a, b)) return w.name();
        }
        return null;
    }

    /**
     * Rejects an LLM response that claims "Cricket World Cup" or "Football World Cup" when no
     * real tournament (see {@link #CRICKET_WORLD_CUPS}/{@link #FOOTBALL_WORLD_CUPS}) actually
     * overlaps the release window — thrown exceptions are caught by the caller's per-candidate
     * try/catch, so the row is simply left unclassified for a future retry rather than written
     * with a hallucinated event.
     */
    private static void validateWorldCupClassification(ReleaseEventParser.EventResult event,
                                                         LocalDate windowStart, LocalDate windowEnd) {
        if ("Cricket World Cup".equals(event.type()) && findOverlap(CRICKET_WORLD_CUPS, windowStart, windowEnd) == null) {
            throw new IllegalStateException(
                "AuraLLM classified 'Cricket World Cup' but no such tournament overlaps " + windowStart + " to " + windowEnd);
        }
        if ("Football World Cup".equals(event.type()) && findOverlap(FOOTBALL_WORLD_CUPS, windowStart, windowEnd) == null) {
            throw new IllegalStateException(
                "AuraLLM classified 'Football World Cup' but no such tournament overlaps " + windowStart + " to " + windowEnd);
        }
    }

    /**
     * Rejects an LLM response with event_type "Election" whose event_name either (a) names a
     * state other than the one {@code stateDisplay} asked about — e.g. a Punjabi movie's window
     * coming back "West Bengal Legislative Assembly Election", observed in a test run — or (b)
     * names the correct state but at a time no real election for it (see {@link
     * #STATE_ELECTIONS}) overlaps the release window. A response naming no state at all (e.g.
     * "Lok Sabha Election", a national election) is left alone — there's no ground-truth table
     * for those here.
     */
    private static void validateStateElectionClassification(ReleaseEventParser.EventResult event, String stateDisplay,
                                                              LocalDate windowStart, LocalDate windowEnd) {
        if (!"Election".equals(event.type()) || event.name() == null) return;
        String lowerName = event.name().toLowerCase();
        java.util.Set<String> expected = stateDisplay == null ? java.util.Set.of() :
            java.util.Arrays.stream(stateDisplay.split(" or ")).map(s -> s.trim().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        for (String knownState : STATE_ELECTIONS.keySet()) {
            if (!lowerName.contains(knownState.toLowerCase())) continue;
            if (!expected.contains(knownState.toLowerCase())) {
                throw new IllegalStateException(
                    "AuraLLM claimed a " + knownState + " election for a release whose language points to " +
                    (stateDisplay == null ? "no specific state" : stateDisplay) + " — cross-state mismatch");
            }
            if (findOverlap(STATE_ELECTIONS.get(knownState), windowStart, windowEnd) == null) {
                throw new IllegalStateException(
                    "AuraLLM claimed a " + knownState + " Assembly election but none is known to overlap " + windowStart + " to " + windowEnd);
            }
            return;
        }
    }

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("releaseevent.enabled", "true"))) {
            log("Disabled via releaseevent.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("releaseevent.initial.delay.ms", "40000"));
        long intervalMs     = Long.parseLong(config.getProperty("releaseevent.interval.hours",   "24")) * 3_600_000L;

        if (!sleep(initialDelayMs, "initial startup delay")) return;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCycle(secrets, config);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logErr("Cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }

            log(String.format("Next cycle in %d hour(s). Sleeping...", intervalMs / 3_600_000L));
            if (!sleep(intervalMs, "inter-cycle interval")) break;
        }
        log("Service stopped.");
    }

    /** Runs exactly one classification cycle synchronously, then returns. For --release-event-scan-once. */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    /**
     * Re-examines every already-released Indian-language row currently classified "Normal" —
     * plus every row still unclassified (NULL/blank release_event_type) — against the full
     * current taxonomy (Holiday/Festival/Election/Cricket World Cup/Football World Cup/Normal),
     * overwriting only rows whose re-classification is no longer "Normal". For
     * --release-event-recheck-normal. Unlike runCycle/runOnce, this targets rows directly
     * (there's no "*_last_checked" gate to respect) and processes its whole batch in a single
     * invocation rather than one candidate.batch.size slice per cycle.
     */
    public void runRecheckNormalOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        log("=== Starting recheck of rows classified 'Normal' or unclassified ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String moviesTable = config.getProperty("table.name", "movies_data_collection");

        String llmUrl        = config.getProperty("llm.url", "http://localhost:1025/api/chat");
        int    llmTimeoutSec = Integer.parseInt(config.getProperty("releaseevent.llm.timeout.seconds", "60"));
        long   llmDelayMs    = Long.parseLong(config.getProperty("releaseevent.llm.delay.ms", "500"));
        int    batchSize     = Integer.parseInt(config.getProperty("releaseevent.recheck.normal.batch.size", "10000"));

        List<ReleaseEventDatabaseService.Candidate> candidates;
        try (ReleaseEventDatabaseService db = new ReleaseEventDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            if (!db.tableExists()) {
                log("Table '" + moviesTable + "' does not yet exist — nothing to recheck.");
                return;
            }
            db.ensureColumnsExist();
            candidates = db.getNormalCandidates(batchSize);
        }

        log(String.format("Found %,d row(s) classified 'Normal' or unclassified to (re)check.", candidates.size()));
        if (candidates.isEmpty()) {
            log("Nothing to do.");
            return;
        }

        CountryResolver countryResolver = new CountryResolver();
        AuraLlmClient llm = new AuraLlmClient(llmUrl, llmTimeoutSec);
        ReleaseEventParser parser = new ReleaseEventParser();
        Map<String, ReleaseEventParser.EventResult> cache = new HashMap<>();

        String today = LocalDate.now().format(ISO_DATE);
        int nonNormal = 0, normal = 0, unresolvedCountry = 0, errors = 0;
        long lastRequestAt = 0;

        try (ReleaseEventDatabaseService db = new ReleaseEventDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            for (int idx = 0; idx < candidates.size(); idx++) {
                ReleaseEventDatabaseService.Candidate c = candidates.get(idx);
                String progress = String.format("[%d/%d]", idx + 1, candidates.size());

                String[] country = countryResolver.resolve(c.language());
                if (country == null) {
                    unresolvedCountry++;
                    continue;
                }
                String countryName = country[1];
                String state = indianStateFor(c.language());
                String cacheKey = c.releaseDate() + "|" + country[0] + "|" + (state == null ? "" : state);

                try {
                    ReleaseEventParser.EventResult event = cache.get(cacheKey);
                    if (event == null) {
                        throttle(lastRequestAt, llmDelayMs);
                        String prompt = buildPrompt(c.releaseDate(), countryName, state);
                        String reply = llm.chat(prompt);
                        lastRequestAt = System.currentTimeMillis();
                        event = parser.parse(reply);
                        LocalDate releaseDate = LocalDate.parse(c.releaseDate(), ISO_DATE);
                        LocalDate windowStart = releaseDate.minusDays(7);
                        LocalDate windowEnd   = releaseDate.plusDays(7);
                        validateWorldCupClassification(event, windowStart, windowEnd);
                        validateStateElectionClassification(event, state, windowStart, windowEnd);
                        cache.put(cacheKey, event);
                    }

                    db.overwriteIfNormal(c.movieName(), c.releaseDate(), c.language(),
                        event.type(), event.name(), event.detail(), today);

                    if ("Normal".equals(event.type())) {
                        normal++;
                        // Most already-checked rows stay "Normal" and would otherwise log
                        // nothing for long stretches, making a healthy run look hung —
                        // surface a heartbeat every so often instead.
                        if ((idx + 1) % 200 == 0) {
                            log(String.format("%s ...still working (last: '%s', still Normal)", progress, c.movieName()));
                        }
                    } else {
                        nonNormal++;
                        log(String.format("%s '%-45s' (%s, %s) — classified %s: %s",
                            progress, c.movieName(), c.releaseDate(), countryName, event.type(), event.name()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    db.rollback();
                    logErr(String.format("%s Error for '%s' (%s, %s): %s",
                        progress, c.movieName(), c.releaseDate(), countryName, e.getMessage()));
                }
            }
        }

        log(String.format(
            "=== Recheck complete — checked: %,d | classified non-Normal: %,d | classified Normal: %,d | " +
            "unresolved country: %,d | errors: %,d ===",
            candidates.size(), nonNormal, normal, unresolvedCountry, errors));
    }

    // ---- cycle ----

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting release-event classification cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String moviesTable = config.getProperty("table.name", "movies_data_collection");

        String llmUrl        = config.getProperty("llm.url", "http://localhost:1025/api/chat");
        int    llmTimeoutSec = Integer.parseInt(config.getProperty("releaseevent.llm.timeout.seconds", "60"));
        long   llmDelayMs    = Long.parseLong(config.getProperty("releaseevent.llm.delay.ms", "500"));
        int    batchSize     = Integer.parseInt(config.getProperty("releaseevent.candidate.batch.size", "300"));
        int    recheckDays   = Integer.parseInt(config.getProperty("releaseevent.recheck.interval.days", "180"));

        List<ReleaseEventDatabaseService.Candidate> candidates;
        try (ReleaseEventDatabaseService db = new ReleaseEventDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            if (!db.tableExists()) {
                log("Table '" + moviesTable + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureColumnsExist();
            candidates = db.getCandidates(batchSize, recheckDays);
        }

        log(String.format(
            "Found %,d Indian-language row(s) awaiting release-event classification (full release_date, not yet classified).",
            candidates.size()));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible row was already classified within the recheck window.");
            return;
        }

        CountryResolver countryResolver = new CountryResolver();
        AuraLlmClient llm = new AuraLlmClient(llmUrl, llmTimeoutSec);
        ReleaseEventParser parser = new ReleaseEventParser();
        Map<String, ReleaseEventParser.EventResult> cache = new HashMap<>();

        String today = LocalDate.now().format(ISO_DATE);
        int classified = 0, cacheHits = 0, unresolvedCountry = 0, errors = 0;
        long lastRequestAt = 0;

        try (ReleaseEventDatabaseService db = new ReleaseEventDatabaseService(dbUrl, dbUser, dbPassword, moviesTable)) {
            for (int idx = 0; idx < candidates.size(); idx++) {
                ReleaseEventDatabaseService.Candidate c = candidates.get(idx);
                String progress = String.format("[%d/%d]", idx + 1, candidates.size());

                String[] country = countryResolver.resolve(c.language());
                if (country == null) {
                    unresolvedCountry++;
                    db.markChecked(c.movieName(), c.releaseDate(), c.language(), today);
                    log(String.format("%s '%-45s' (%s, %s) — language does not resolve to a country. Skipping.",
                        progress, c.movieName(), c.releaseDate(), c.language()));
                    continue;
                }
                String countryName = country[1];
                String state = indianStateFor(c.language());
                String cacheKey = c.releaseDate() + "|" + country[0] + "|" + (state == null ? "" : state);

                try {
                    ReleaseEventParser.EventResult event = cache.get(cacheKey);
                    if (event != null) {
                        cacheHits++;
                    } else {
                        throttle(lastRequestAt, llmDelayMs);
                        String prompt = buildPrompt(c.releaseDate(), countryName, state);
                        String reply = llm.chat(prompt);
                        lastRequestAt = System.currentTimeMillis();
                        event = parser.parse(reply);
                        LocalDate releaseDate = LocalDate.parse(c.releaseDate(), ISO_DATE);
                        LocalDate windowStart = releaseDate.minusDays(7);
                        LocalDate windowEnd   = releaseDate.plusDays(7);
                        validateWorldCupClassification(event, windowStart, windowEnd);
                        validateStateElectionClassification(event, state, windowStart, windowEnd);
                        cache.put(cacheKey, event);
                    }

                    db.updateEventIfMissing(c.movieName(), c.releaseDate(), c.language(),
                        event.type(), event.name(), event.detail(), today);
                    classified++;
                    log(String.format("%s '%-45s' (%s, %s) — %s: %s",
                        progress, c.movieName(), c.releaseDate(), countryName, event.type(), event.name()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    db.rollback();
                    logErr(String.format("%s Error for '%s' (%s, %s): %s",
                        progress, c.movieName(), c.releaseDate(), countryName, e.getMessage()));
                }
            }
        }

        log(String.format(
            "=== Cycle complete — candidates: %,d | classified: %,d | AuraLLM calls saved by cache: %,d | " +
            "unresolved country: %,d | errors: %,d (rechecked in %d day(s)) ===",
            candidates.size(), classified, cacheHits, unresolvedCountry, errors, recheckDays));
    }

    /** @param indianState home state for the movie's language (see {@link #indianStateFor}), or null */
    private static String buildPrompt(String releaseDate, String countryName, String indianState) {
        LocalDate date = LocalDate.parse(releaseDate, ISO_DATE);
        LocalDate windowStartDate = date.minusDays(7);
        LocalDate windowEndDate   = date.plusDays(7);
        String windowStart = windowStartDate.format(ISO_DATE);
        String windowEnd   = windowEndDate.format(ISO_DATE);

        // Ground truth, not LLM recall (see STATE_ELECTIONS javadoc): a test run once attached
        // "Kerala Assembly Election"/"Tamil Nadu Assembly Election" to March-May 2017 releases —
        // those states' real elections were 2016 and 2021, a full year off.
        String stateElectionOverlap = findStateElectionOverlap(indianState, windowStartDate, windowEndDate);
        String stateClause;
        if (indianState == null) {
            stateClause = "";
        } else if (stateElectionOverlap != null) {
            stateClause = "Verified fact: the " + stateElectionOverlap + " (voting or result day) falls within " +
                "this window — that takes priority: classify it as \"Election\" with event_name naming that " +
                "specific state election.\n\n";
        } else {
            stateClause = "Verified fact: no " + indianState + " Legislative Assembly election is known to fall " +
                "within this window. Do NOT classify as a " + indianState + " state election even if you recall " +
                "one being nearby — use \"Normal\" or another category instead (a national election is still " +
                "possible; only the state-election claim is ruled out).\n\n";
        }

        // Ground truth, not LLM recall: dates below are checked in Java (see CRICKET_WORLD_CUPS/
        // FOOTBALL_WORLD_CUPS) and any World Cup classification outside them is rejected before
        // being written — a test run once had the model confuse a June-2017 release with a FIFA
        // World Cup that didn't happen that year.
        String cricketOverlap  = findOverlap(CRICKET_WORLD_CUPS, windowStartDate, windowEndDate);
        String footballOverlap = findOverlap(FOOTBALL_WORLD_CUPS, windowStartDate, windowEndDate);
        String worldCupFactClause;
        if (cricketOverlap != null || footballOverlap != null) {
            StringBuilder sb = new StringBuilder("Verified fact: ");
            if (cricketOverlap != null)  sb.append("the ").append(cricketOverlap).append(" is underway during this window. ");
            if (footballOverlap != null) sb.append("the ").append(footballOverlap).append(" is underway during this window. ");
            sb.append("If a specific match involving ").append(countryName)
              .append("'s national team (or another globally high-profile match) plausibly falls on or near the " +
                      "release date, classify using the matching category below; otherwise prefer a more directly " +
                      "relevant category instead.\n\n");
            worldCupFactClause = sb.toString();
        } else {
            worldCupFactClause = "Verified fact: no Cricket World Cup or Football World Cup tournament is scheduled " +
                "within this window. Do NOT classify as \"Cricket World Cup\" or \"Football World Cup\" even if you " +
                "recall one being nearby — use \"Normal\" or another category instead.\n\n";
        }

        return "Role: You are a film-industry release-calendar expert with deep knowledge of public " +
            "holidays, cultural/religious festivals, and elections worldwide.\n\n" +
            "Movie release date: " + releaseDate + " (YYYY-MM-DD)\n" +
            "Country: " + countryName + "\n" +
            "Window to check: " + windowStart + " to " + windowEnd + " (7 days before/after release)\n\n" +
            worldCupFactClause +
            "Determine the single most significant event in " + countryName + " that falls within this " +
            "window and would be relevant to a movie's box-office performance. Use these exact category " +
            "definitions:\n" +
            "- \"Festival\": a major cultural or religious festival (e.g. Diwali, Eid ul-Fitr, Eid ul-Adha, " +
            "Christmas, Holi, Onam, Pongal, Durga Puja, Ganesh Chaturthi, Navratri, Baisakhi, Vishu).\n" +
            "- \"Holiday\": a public/national holiday or widely observed occasion not covered above " +
            "(e.g. Independence Day, Republic Day, Labour Day, New Year's Day, Valentine's Day, " +
            "Children's Day, Mother's Day, Thanksgiving).\n" +
            "- \"Election\": a national or major state/regional election (voting day or result day) in " +
            countryName + ".\n" +
            "- \"Cricket World Cup\": only when the verified fact above confirms one is underway, and a " +
            "match plausibly falls on or near the release date.\n" +
            "- \"Football World Cup\": only when the verified fact above confirms one is underway, and a " +
            "match plausibly falls on or near the release date.\n" +
            "- \"Normal\": none of the above fall within the window.\n\n" +
            stateClause +
            "If more than one event falls in the window, pick only the single most significant one for " +
            "box-office impact. Respond ONLY with a single valid JSON object and nothing else — no markdown " +
            "fences, no commentary, no explanation before or after, no self-corrections or parenthetical " +
            "asides inside the field values — in this exact shape:\n" +
            "{\"event_type\":\"Holiday|Festival|Election|Cricket World Cup|Football World Cup|Normal\"," +
            "\"event_name\":\"specific name, e.g. Onam, Pongal, Valentine's Day, Tamil Nadu Assembly Election, " +
            "ICC T20 World Cup — India vs Pakistan — or Normal when event_type is Normal\"," +
            "\"event_detail\":\"one short sentence (max ~25 words) of useful context, e.g. the exact date " +
            "within the window and its significance\"}";
    }

    // ---- helpers ----

    private boolean sleep(long ms, String reason) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted during " + reason + " — stopping.");
            return false;
        }
    }

    private void throttle(long lastRequestAt, long delayMs) throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAt;
        if (lastRequestAt > 0 && elapsed < delayMs) Thread.sleep(delayMs - elapsed);
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
    }

    private void logErr(String msg) {
        System.err.println(PREFIX + msg);
        System.err.flush();
    }

    private Properties loadProperties(String resourceName, boolean required) {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                if (required) throw new RuntimeException(resourceName + " not found on classpath");
                return props;
            }
            props.load(is);
        } catch (IOException e) {
            if (required) throw new RuntimeException("Cannot load " + resourceName, e);
        }
        return props;
    }
}
