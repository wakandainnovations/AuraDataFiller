package com.lit.fire.flame.kannadabudget;

import com.lit.fire.flame.marketing.AuraLlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot backfill of budget/revenue (USD) for Kannada movies in movies_data_collection, sourced
 * from a single mtwikiblog.com article that lists Karnataka-territory box office collection,
 * budget, and verdict for movies released around 2022, 2023, and 2026 (see
 * KannadaBudgetPageParser for the article's section layout).
 *
 * The article mixes in pan-India films tracked only for their Karnataka-territory collection
 * (RRR, Pushpa, Salaar, Kalki, etc. — not actual Kannada movies). Per-row handling:
 *   - Row's movie_name already has a DB entry with language = 'kannada': fill budget/revenue
 *     only if currently 0/missing (never clobber a better existing value).
 *   - Row's movie_name already has a DB entry with language = 'unknown': relabel that row's
 *     language to 'kannada' and fill budget/revenue as above.
 *   - Row's movie_name already has a DB entry with some other language (e.g. 'telugu'): skipped
 *     entirely — that's a real non-Kannada movie already correctly recorded.
 *   - Row's movie_name has no DB entry at all: inserted as a brand-new 'kannada' row (this is a
 *     deliberate, explicitly-requested choice — see App.java's --kannada-budget-import comment —
 *     so pan-India films not yet otherwise in the DB do get inserted mislabeled as 'kannada'
 *     rather than skipped). release_date is asked of AuraLLM; if it can't give a confident
 *     YYYY-MM-DD answer, January 1st of the article's section year is used instead.
 *
 * INR->USD conversion uses fixed rates for the article's three section years (as directed,
 * rather than xe.com historical rates): 2022=80, 2023=83, 2026=95 INR per 1 USD.
 */
public class KannadaBudgetBackfillService {

    private static final String PREFIX = "[KANNADA-BUDGET] ";
    private static final Map<Integer, Double> INR_PER_USD = Map.of(2022, 80.0, 2023, 83.0, 2026, 95.0);
    private static final long INR_PER_CRORE = 10_000_000L;
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");
        String llmUrl     = config.getProperty("llm.url", "http://localhost:1025/api/chat");
        int    llmTimeoutSec = Integer.parseInt(config.getProperty("kannadabudget.llm.timeout.seconds", "60"));
        long   llmDelayMs    = Long.parseLong(config.getProperty("kannadabudget.llm.delay.ms", "500"));

        KannadaBudgetPageParser parser = new KannadaBudgetPageParser();
        String articlePath = "/2018/10/kannada-box-office-collection-budget-verdict-hit-or-flop.html";
        if (!parser.isPathAllowed(articlePath)) {
            log("Skipping — " + articlePath + " disallowed by robots.txt.");
            return;
        }

        log("Fetching and parsing " + KannadaBudgetPageParser.PAGE_URL + " ...");
        List<KannadaBudgetPageParser.Row> rows = parser.fetchAndParse();
        log(String.format("Parsed %,d row(s) across recognised year sections.", rows.size()));
        if (rows.isEmpty()) {
            log("Nothing to do — no rows parsed (page structure may have changed).");
            return;
        }

        AuraLlmClient llm = new AuraLlmClient(llmUrl, llmTimeoutSec);
        Stats stats = new Stats();

        try (KannadaBudgetDatabaseService db = new KannadaBudgetDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            for (KannadaBudgetPageParser.Row row : rows) {
                stats.rows++;
                double rate = INR_PER_USD.get(row.year());
                Long budgetUsd  = toUsd(row.budgetCr(), rate);
                Long revenueUsd = toUsd(row.revenueCr(), rate);

                try {
                    List<KannadaBudgetDatabaseService.ExistingRow> existing = db.findByName(row.movieName());

                    if (existing.isEmpty()) {
                        String releaseDate = resolveReleaseDate(llm, row.movieName(), row.year(), llmDelayMs);
                        boolean inserted = db.insertNewRow(row.movieName(), releaseDate, budgetUsd, revenueUsd);
                        if (inserted) {
                            stats.inserted++;
                            log(String.format("Inserted '%-45s' (%s, new) | budget: %s | revenue: %s",
                                row.movieName(), releaseDate, usd(budgetUsd), usd(revenueUsd)));
                        } else {
                            stats.skippedDuplicate++;
                        }
                        continue;
                    }

                    for (KannadaBudgetDatabaseService.ExistingRow existingRow : existing) {
                        String lang = existingRow.language() == null ? "" : existingRow.language().trim().toLowerCase();
                        if (lang.equals("kannada") || lang.equals("unknown")) {
                            // This page mixes in pan-India films tracked only for their
                            // Karnataka-territory collection (Coolie, Leo, Vettaiyan, etc. — not
                            // actually Kannada). An 'unknown' row matching one of those by name
                            // must NOT be relabelled just because it appears on this page —
                            // confirm with AuraLLM first. Budget/revenue are filled either way;
                            // only the relabel is gated.
                            boolean relabel = lang.equals("unknown") &&
                                confirmIsKannadaMovie(llm, existingRow.movieName(), llmDelayMs);
                            int updated = db.fillFinanceAndMaybeRelabel(
                                existingRow.movieName(), existingRow.releaseDate(), existingRow.language(),
                                budgetUsd, revenueUsd, relabel);
                            if (updated > 0) {
                                stats.updated++;
                                log(String.format("Updated  '%-45s' (%s, %s%s) | budget: %s | revenue: %s",
                                    existingRow.movieName(), existingRow.releaseDate(),
                                    existingRow.language(), relabel ? "->kannada" : "",
                                    usd(budgetUsd), usd(revenueUsd)));
                            } else {
                                stats.noChange++;
                            }
                        } else {
                            stats.skippedNonKannada++;
                        }
                    }
                } catch (Exception e) {
                    stats.errors++;
                    db.rollback();
                    logErr("Error for '" + row.movieName() + "' (" + row.year() + "): " + e.getMessage());
                }
            }
        }

        log(String.format(
            "Done — rows: %,d | updated (filled/relabelled): %,d | inserted: %,d | " +
            "skipped (non-Kannada existing entry): %,d | skipped (duplicate insert): %,d | " +
            "no change needed: %,d | errors: %,d",
            stats.rows, stats.updated, stats.inserted, stats.skippedNonKannada,
            stats.skippedDuplicate, stats.noChange, stats.errors));
    }

    // ---- AuraLLM Kannada-language confirmation (gates 'unknown'->'kannada' relabelling only) ----

    /**
     * Defaults to false (don't relabel) on any ambiguous/failed reply or error — relabelling a
     * row incorrectly is the risk being guarded against here, so an inconclusive answer must
     * leave the row as 'unknown' rather than risk mislabelling a non-Kannada film.
     */
    private boolean confirmIsKannadaMovie(AuraLlmClient llm, String movieName, long delayMs) {
        try {
            Thread.sleep(delayMs);
            String prompt = "Is the film \"" + movieName + "\" primarily a Kannada-language " +
                "(Karnataka film industry) movie, as opposed to a Telugu/Tamil/Hindi/Malayalam " +
                "or other-language film? Reply with ONLY one word: YES or NO.";
            String reply = llm.chat(prompt);
            if (reply == null) return false;
            String normalized = reply.trim().toLowerCase();
            return normalized.startsWith("yes");
        } catch (Exception e) {
            logErr("AuraLLM Kannada-language check failed for '" + movieName + "': " + e.getMessage() +
                " — leaving language unchanged.");
            return false;
        }
    }

    // ---- AuraLLM release-date lookup ----

    private String resolveReleaseDate(AuraLlmClient llm, String movieName, int year, long delayMs) {
        String fallback = LocalDate.of(year, 1, 1).toString();
        try {
            Thread.sleep(delayMs);
            String prompt = "What is the exact theatrical release date of the Kannada-language " +
                "film \"" + movieName + "\" that released around " + year + "? " +
                "Reply with ONLY the date in strict YYYY-MM-DD format and nothing else. " +
                "If you are not confident of the exact date, reply with exactly: UNKNOWN";
            String reply = llm.chat(prompt);
            if (reply == null) return fallback;

            Matcher m = ISO_DATE.matcher(reply);
            if (!m.find()) return fallback;

            LocalDate parsed;
            try {
                parsed = LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception e) {
                return fallback;
            }
            int parsedYear = parsed.getYear();
            if (parsedYear < year - 1 || parsedYear > year + 1) return fallback;
            return parsed.toString();
        } catch (Exception e) {
            logErr("AuraLLM release-date lookup failed for '" + movieName + "': " + e.getMessage() +
                " — using " + fallback + ".");
            return fallback;
        }
    }

    // ---- helpers ----

    private Long toUsd(Double crore, double inrPerUsd) {
        if (crore == null) return null;
        return Math.round(crore * INR_PER_CRORE / inrPerUsd);
    }

    private String usd(Long amount) {
        return amount == null ? "n/a" : "$" + String.format("%,d", amount);
    }

    private static final class Stats {
        long rows, updated, inserted, skippedNonKannada, skippedDuplicate, noChange, errors;
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
