package com.lit.fire.flame.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Background service that classifies each movie's promotional campaign into the marketing
 * tactic taxonomy in MarketingTacticTaxonomy, via AuraLLM, and stores the result in
 * movie_marketing_tactics (see MarketingTacticsDatabaseService).
 *
 * Candidates are already-released movies from movies_data_collection AND managed_entities
 * (type='MOVIE'), released within the last marketingtactics.lookback.years years (default 20),
 * most-recently-released first — deliberately not restricted to Indian languages, unlike most
 * of this app's other enrichment services, since marketing-tactic classification is not
 * language-specific.
 *
 * Thread model mirrors CreditsCrawlerService: run() loops forever with a configurable interval
 * (default 24h); runOnce() does a single cycle for one-shot/manual invocation
 * (--marketing-tactics-scan-once).
 */
public class MarketingTacticsService implements Runnable {

    private static final String PREFIX = "[MARKETING] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("marketingtactics.enabled", "true"))) {
            log("Disabled via marketingtactics.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("marketingtactics.initial.delay.ms", "35000"));
        long intervalMs     = Long.parseLong(config.getProperty("marketingtactics.interval.hours",   "24")) * 3_600_000L;

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

    /** Runs exactly one classification cycle synchronously, then returns. For --marketing-tactics-scan-once. */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    /** Looks up and prints (as JSON) every marketing tactic on file for one movie. For --marketing-tactics-lookup. */
    public void printLookup(String movieName, String language, String year) throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");

        try (MarketingTacticsDatabaseService db = new MarketingTacticsDatabaseService(dbUrl, dbUser, dbPassword)) {
            List<MarketingTacticsDatabaseService.TacticRecord> records = db.getTactics(movieName, language, year);
            ObjectMapper mapper = new ObjectMapper();
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(records));
        }
    }

    // ---- cycle ----

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting marketing-tactics classification cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String moviesTable = config.getProperty("table.name", "movies_data_collection");

        String llmUrl        = config.getProperty("llm.url", "http://localhost:1025/api/chat");
        int    llmTimeoutSec = Integer.parseInt(config.getProperty("marketingtactics.llm.timeout.seconds", "120"));
        long   llmDelayMs    = Long.parseLong(config.getProperty("marketingtactics.llm.delay.ms", "500"));
        int    lookbackYears = Integer.parseInt(config.getProperty("marketingtactics.lookback.years", "20"));
        int    batchSize     = Integer.parseInt(config.getProperty("marketingtactics.candidate.batch.size", "200"));
        int    recheckDays   = Integer.parseInt(config.getProperty("marketingtactics.recheck.interval.days", "180"));

        List<MarketingTacticsDatabaseService.Candidate> candidates;
        boolean includeManagedEntities;
        try (MarketingTacticsDatabaseService db = new MarketingTacticsDatabaseService(dbUrl, dbUser, dbPassword)) {
            if (!db.tableExists(moviesTable)) {
                log("Table '" + moviesTable + "' does not yet exist — skipping cycle.");
                return;
            }
            db.ensureSchema();
            includeManagedEntities = db.tableExists("managed_entities");
            if (!includeManagedEntities) {
                log("Table 'managed_entities' not found — scanning " + moviesTable + " only.");
            }
            candidates = db.getCandidates(moviesTable, includeManagedEntities, lookbackYears, recheckDays, batchSize);
        }

        int cutoffYear = java.time.Year.now().getValue() - lookbackYears;
        log(String.format(
            "Found %,d movie(s) awaiting marketing-tactics classification (released %d-%d, most recent first, including managed_entities: %s).",
            candidates.size(), cutoffYear, java.time.Year.now().getValue(), includeManagedEntities));

        if (candidates.isEmpty()) {
            log("Nothing to do — every eligible movie was already classified within the recheck window.");
            return;
        }

        AuraLlmClient llm = new AuraLlmClient(llmUrl, llmTimeoutSec);
        MarketingTacticsParser parser = new MarketingTacticsParser();
        String tacticListing = MarketingTacticTaxonomy.promptListing();

        int classified = 0, noData = 0, errors = 0;
        long lastRequestAt = 0;

        try (MarketingTacticsDatabaseService db = new MarketingTacticsDatabaseService(dbUrl, dbUser, dbPassword)) {
            for (int idx = 0; idx < candidates.size(); idx++) {
                MarketingTacticsDatabaseService.Candidate c = candidates.get(idx);
                String progress = String.format("[%d/%d]", idx + 1, candidates.size());

                throttle(lastRequestAt, llmDelayMs);
                try {
                    String prompt = buildPrompt(c.movieName(), c.language(), c.year(), tacticListing);
                    String reply = llm.chat(prompt);
                    lastRequestAt = System.currentTimeMillis();

                    Map<Integer, List<String>> tactics = parser.parse(reply);
                    if (tactics.isEmpty()) {
                        db.markScannedNoData(c.movieName(), c.year(), c.language());
                        noData++;
                        log(String.format("%s '%-45s' (%s, %s) — no tactics identified.",
                            progress, c.movieName(), c.year(), c.language()));
                    } else {
                        db.replaceTactics(c.movieName(), c.year(), c.language(), tactics);
                        classified++;
                        log(String.format("%s '%-45s' (%s, %s) — %d classification(s) filled.",
                            progress, c.movieName(), c.year(), c.language(), tactics.size()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    db.rollback();
                    logErr(String.format("%s Error for '%s' (%s, %s): %s",
                        progress, c.movieName(), c.year(), c.language(), e.getMessage()));
                }
            }
        }

        log(String.format(
            "=== Cycle complete — candidates: %,d | classified: %,d | no tactics found: %,d | errors: %,d (rechecked in %d day(s)) ===",
            candidates.size(), classified, noData, errors, recheckDays));
    }

    private static String buildPrompt(String movieName, String language, String year, String tacticListing) {
        return "Role: You are a movie marketing expert with deep knowledge of film promotional campaigns worldwide.\n\n" +
            "Movie: \"" + movieName + "\"\n" +
            "Language: " + language + "\n" +
            "Release year: " + year + "\n\n" +
            "Based on your knowledge of this specific movie's actual promotional campaign, identify which of the " +
            "following marketing tactic classifications were used. For every classification that WAS used, write " +
            "one entry per distinct thing the movie's marketing team actually did for it. Each entry must be a " +
            "detailed, specific, narrative account — 2 to 4 full sentences, the way an entertainment journalist " +
            "would tell the story — naming the actual people, platforms, events, and outcomes involved. Do NOT " +
            "write a short label or a generic restatement of the classification name; write what actually " +
            "happened, in detail. For example, an entry for 'Manufactured \"Leaks\"' on the movie Marty Supreme " +
            "should read like this, not like \"leaked footage on social media\":\n" +
            "\"A conspiracy theory began organically on TikTok that Chalamet was actually the anonymous British " +
            "rapper 'EsDeeKid'. Instead of debunking it, the marketing team leaned into the mystery. This led to " +
            "Chalamet posting an Instagram Reel rapping the words 'Marty Supreme,' which racked up millions of " +
            "likes and acted as a surprise music drop.\"\n" +
            "Match that level of specificity and narrative detail for every entry. For every classification NOT " +
            "used, or that you are not reasonably confident was used, return an empty array. Do not guess or " +
            "invent details you're not confident about — a detailed entry must still be factually accurate, not " +
            "just elaborately worded.\n\n" +
            "Marketing tactic classifications:\n" + tacticListing + "\n" +
            "Respond ONLY with a single valid JSON object and nothing else — no markdown fences, no commentary, " +
            "no explanation before or after. The object must have exactly one key per classification number above " +
            "(as a JSON string, e.g. \"1\"), each mapping to a JSON array of detailed narrative strings as " +
            "described above. Use an empty array [] for classifications that don't apply. Example shape: " +
            "{\"1\": [\"...\"], \"2\": [], \"3\": [\"...\", \"...\"]}";
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
