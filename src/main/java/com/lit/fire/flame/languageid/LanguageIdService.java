package com.lit.fire.flame.languageid;

import com.lit.fire.flame.marketing.AuraLlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

/**
 * Background service that identifies the language of movies_data_collection rows currently
 * marked language = 'unknown', via AuraLLM, and writes the resolved language back.
 *
 * movies_data_collection is a general worldwide movie catalog (not Indian-cinema-specific) with
 * ~7,000 unknown rows as of 2026-09-13 — far more than one process cycle should attempt
 * synchronously, so (mirroring RuntimeBudgetCrawlerService/CreditsCrawlerService) this processes
 * a bounded batch per cycle (languageid.batch.size) and repeats every languageid.interval.hours,
 * making steady progress across the whole backlog over multiple cycles rather than one huge run.
 *
 * A row AuraLLM can't confidently resolve is marked "checked today" so it isn't retried every
 * single cycle forever (languageid.recheck.interval.days) — see LanguageIdDatabaseService. A row
 * successfully resolved never matches the candidate query again (its language is no longer
 * 'unknown'), so it needs no such bookkeeping.
 *
 * Thread model mirrors CreditsCrawlerService/RuntimeBudgetCrawlerService: run() loops forever
 * with a configurable interval; runOnce() does a single batch for one-shot/manual invocation
 * (--language-id-scan-once).
 */
public class LanguageIdService implements Runnable {

    private static final String PREFIX = "[LANGUAGE-ID] ";

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("languageid.enabled", "true"))) {
            log("Disabled via languageid.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(config.getProperty("languageid.initial.delay.ms", "30000"));
        long intervalMs     = Long.parseLong(config.getProperty("languageid.interval.hours",   "24")) * 3_600_000L;

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

    /**
     * Runs exactly one batch synchronously, then returns. Intended for the
     * {@code --language-id-scan-once} CLI mode; call repeatedly (or use {@code run()}/--watch)
     * to work through the full backlog.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        runCycle(secrets, config);
    }

    private void runCycle(Properties secrets, Properties config) throws Exception {
        log("=== Starting language identification cycle ===");

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");
        String llmUrl     = config.getProperty("llm.url", "http://localhost:1025/api/chat");

        int    batchSize     = Integer.parseInt(config.getProperty("languageid.batch.size", "500"));
        int    recheckDays   = Integer.parseInt(config.getProperty("languageid.recheck.interval.days", "14"));
        long   llmDelayMs    = Long.parseLong(config.getProperty("languageid.llm.delay.ms", "400"));
        int    llmTimeoutSec = Integer.parseInt(config.getProperty("languageid.llm.timeout.seconds", "60"));

        List<LanguageIdDatabaseService.Candidate> candidates;
        try (LanguageIdDatabaseService db = new LanguageIdDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            db.ensureColumnsExist();
            candidates = db.getUnknownLanguageMovies(batchSize, recheckDays);
        }
        log(String.format("Found %,d row(s) with language='unknown' due for a check " +
            "(batch size %,d, %,d-day recheck cooldown).", candidates.size(), batchSize, recheckDays));

        if (candidates.isEmpty()) {
            log("Nothing to do this cycle — every unknown row was checked within the last " +
                recheckDays + " day(s), or none remain.");
            return;
        }

        AuraLlmClient llm = new AuraLlmClient(llmUrl, llmTimeoutSec);
        int resolved = 0, unresolved = 0, errors = 0;
        long lastRequestAt = 0;

        try (LanguageIdDatabaseService db = new LanguageIdDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            String today = LocalDate.now().toString();

            for (LanguageIdDatabaseService.Candidate c : candidates) {
                throttle(lastRequestAt, llmDelayMs);

                try {
                    String year = (c.releaseDate() != null && c.releaseDate().length() >= 4)
                        ? c.releaseDate().substring(0, 4) : null;
                    String prompt = "What language(s) was the film \"" + c.movieName() + "\"" +
                        (year != null ? " (released around " + year + ")" : "") + " ORIGINALLY made/filmed in? " +
                        "Most films are made in a single language — in that case reply with ONLY that " +
                        "language name in English, in lowercase, one or two words " +
                        "(e.g. hindi, tamil, english, mandarin chinese). " +
                        "Only if the film was actually shot simultaneously as a true multilingual production in " +
                        "more than one original language (common for some Indian films, e.g. shot together in " +
                        "Tamil, Telugu and Hindi) — NOT merely dubbed or subtitled afterward for other markets — " +
                        "reply with all of those original languages as a comma-separated list, lowercase " +
                        "(e.g. tamil, telugu, hindi). If you don't know or aren't confident, reply with exactly: UNKNOWN";
                    String reply = llm.chat(prompt);
                    lastRequestAt = System.currentTimeMillis();

                    String language = LanguageIdParser.parse(reply);
                    // Observed once in practice ("Alpha" (2018), a film with a constructed
                    // in-universe language, answered "alpha") — the model echoing the movie's
                    // own title back as if it were a language name. Treat that as unresolved
                    // rather than writing the title into the language column.
                    if (language != null && language.equalsIgnoreCase(c.movieName().trim())) {
                        language = null;
                    }
                    if (language == null) {
                        unresolved++;
                        db.markChecked(c.movieName(), c.releaseDate(), today);
                        continue;
                    }

                    int updated = db.resolveLanguage(c.movieName(), c.releaseDate(), language);
                    if (updated > 0) {
                        resolved++;
                        log(String.format("Resolved '%-45s' (%s) -> %s",
                            c.movieName(), c.releaseDate(), language));
                    } else {
                        unresolved++;
                        db.markChecked(c.movieName(), c.releaseDate(), today);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastRequestAt = System.currentTimeMillis();
                    errors++;
                    db.rollback();
                    logErr(String.format("Error for '%s' (%s): %s", c.movieName(), c.releaseDate(), e.getMessage()));
                    // Most errors here are a deterministic PK conflict (a duplicate row already
                    // exists under the resolved language) rather than a transient failure —
                    // without this, the exact same row would fail identically every single
                    // cycle forever instead of backing off like an unresolved row does.
                    try {
                        db.markChecked(c.movieName(), c.releaseDate(), today);
                    } catch (Exception markEx) {
                        logErr("Also failed to mark '" + c.movieName() + "' checked: " + markEx.getMessage());
                    }
                }
            }
        }

        log(String.format(
            "=== Cycle complete — candidates: %,d | resolved: %,d | unresolved (rechecked in %d day(s)): %,d | errors: %,d ===",
            candidates.size(), resolved, recheckDays, unresolved, errors));
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
