package com.lit.fire.flame.legacycsv;

import com.lit.fire.flame.crawler.ExchangeRateService;
import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;
import com.lit.fire.flame.csv.XlsxParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-shot backfill that fills pre-existing columns in movies_data_collection (genre,
 * release_event_type, revenue, budget, number_of_screens, runtime, rating_10) from historical
 * Bollywood CSV/XLSX files dropped in {@code legacycsv.folder} — see application.properties.
 * Each sheet of an .xlsx workbook is processed the same way a CSV file is; sheets with no
 * recognisable movie-name header (e.g. a workbook's summary-statistics tabs) are skipped.
 *
 * These source files carry no release_date/language, only a movie title, so rows are matched
 * to the DB by (movie_name, language='hindi') and only when that match is unique; ambiguous or
 * unmatched titles are skipped and counted in the summary rather than guessed at. No new
 * columns are ever created — every target field above already exists in the table — and a
 * field already populated in the DB is never overwritten (fill-if-missing only, same
 * convention as CreditsDatabaseService/RuntimeBudgetDatabaseService).
 *
 * Revenue/budget columns may be denominated in either INR or USD depending on the source
 * file — the currency is read off the header itself (e.g. "Revenue(INR)" vs "Budget_USD").
 * INR figures are converted to whole USD via the same xe.com historical-rate lookup (keyed
 * off the matched row's own release_date, and cached in currency_rate_xe) used everywhere
 * else in this codebase, so the stored values stay consistent with revenue/budget populated
 * by the box-office crawlers; USD figures are stored as-is. A header with no currency marker
 * is assumed to be INR (this dataset's historical convention).
 *
 * A title with no unique match against existing Hindi rows is, ONLY for CSV files named in
 * {@code legacycsv.insert.new.movies.files}, treated as a movie missing from the DB entirely
 * and inserted as a brand-new row (release_date left as "Unknown" — the source carries no date
 * — for the user to reconcile by hand later). That property is an explicit, hand-curated
 * allowlist rather than "every CSV in the folder": a file dropped in here isn't necessarily
 * Hindi/Bollywood data at all (e.g. a file literally named tamil_movies_*.csv), and any file not
 * on the list — including the .xlsx workbook, which is never eligible — just has its unmatched
 * titles counted and skipped, never inserted. An INR revenue/budget can't be inserted for a
 * brand-new row either, since there's no release year to pick a historical exchange rate against
 * — only USD-denominated money fields are kept in that case.
 *
 * A row with release_date "Unknown" is permanently excluded from matching in
 * findUniqueHindiMatch, on purpose: once created it must stay frozen, because matching by title
 * alone against a row with no year is exactly how an unrelated same-titled movie from a
 * completely different file/language could otherwise silently overwrite its runtime/budget/
 * rating with a different movie's real facts (this happened once, with a Bollywood "Fight Club"
 * picking up the 1999 Hollywood "Fight Club"'s budget/runtime/IMDb rating from the TMDb xlsx —
 * see LegacyCsvDatabaseService.findUniqueHindiMatch).
 */
public class LegacyCsvBackfillService {

    private static final String PREFIX = "[LEGACY-CSV] ";

    /** Normalized CSV header → semantic field this importer understands. */
    private enum Field { MOVIE_NAME, RELEASE_PERIOD, GENRE, SCREENS, REVENUE, BUDGET, RUNTIME, RATING }

    private enum Currency { INR, USD }

    /** Header→field mapping for one CSV, plus the detected currency for REVENUE/BUDGET. */
    private record HeaderMap(Map<Field, String> headers, Map<Field, Currency> currencies) {}

    /**
     * Runs exactly one backfill pass over every *.csv file in the configured folder, then
     * returns. Intended for the {@code --legacy-csv-import} CLI mode.
     */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");
        String folder      = config.getProperty("legacycsv.folder",
            "/Users/mukundv/Documents/work/space/new_data_collection");
        Set<String> trustedFiles = Arrays.stream(
                config.getProperty("legacycsv.insert.new.movies.files", "Data_for_repository.csv,Kaggle_1.csv")
                    .split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        File dir = new File(folder);
        File[] dataFiles = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".csv") || lower.endsWith(".xlsx");
        });
        if (dataFiles == null || dataFiles.length == 0) {
            log("No CSV/XLSX files found in " + folder + " — nothing to do.");
            return;
        }
        Arrays.sort(dataFiles, Comparator.comparing(File::getName));

        ExchangeRateService exchangeRate = new ExchangeRateService();
        Stats totals = new Stats();

        try (LegacyCsvDatabaseService db = new LegacyCsvDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            db.ensureRateTableExists();
            log("Ensuring lower(trim(movie_name)) index exists (speeds up title matching)...");
            db.ensureNameMatchIndex();
            Map<String, Double> existingRates = db.getExistingRates("INR", "USD");
            exchangeRate.preloadCache(existingRates);
            log(String.format("Pre-loaded %,d exchange rate(s) from currency_rate_xe.", existingRates.size()));

            for (File dataFile : dataFiles) {
                if (dataFile.getName().toLowerCase().endsWith(".xlsx")) {
                    processXlsxFile(dataFile, db, exchangeRate, totals);
                } else {
                    log("=== " + dataFile.getName() + " ===");
                    boolean trusted = trustedFiles.contains(dataFile.getName().toLowerCase());
                    if (!trusted) {
                        log("  Not on legacycsv.insert.new.movies.files — report-only: matches are " +
                            "counted but nothing is written (no insert, no field fill).");
                    }
                    CsvData csv = new CsvParser().parse(dataFile.getAbsolutePath());
                    processTable(dataFile.getName(), csv, db, exchangeRate, totals, trusted);
                }
            }

            Map<String, Double> newRates = exchangeRate.getNewlyFetchedRates();
            for (Map.Entry<String, Double> e : newRates.entrySet()) {
                db.upsertExchangeRate(e.getKey(), "INR", "USD", e.getValue());
            }
            if (!newRates.isEmpty()) {
                log(String.format("Saved %,d new exchange rate(s) to currency_rate_xe.", newRates.size()));
            }
        }

        log(String.format(
            "Done — rows: %,d | updated: %,d | inserted (new movies): %,d | matched but not " +
            "trusted (not written): %,d | unmatched: %,d | ambiguous (skipped): %,d | " +
            "no usable fields: %,d | rate lookup failed: %,d | " +
            "INR money dropped on insert (no year to rate-convert): %,d",
            totals.rows, totals.updated, totals.inserted, totals.matchedButNotTrusted,
            totals.unmatched, totals.ambiguous, totals.noFields, totals.rateLookupFailed,
            totals.moneyDroppedOnInsert));
    }

    /**
     * Processes only the FIRST sheet of the workbook that has a recognisable movie-name header.
     * Deliberately does not walk every matching sheet: multi-tab analysis workbooks (like this
     * one, with "1_Raw_Data" alongside "2_Cleaning"/"3_Mean_Median_Mode" tabs) commonly repeat
     * the same table further in with missing values statistically imputed (median/mode-filled)
     * for cleaning/statistics purposes — importing that copy would write fabricated numbers
     * into genuine DB fields the real source left blank, indistinguishable from real data.
     * The first occurrence is taken to be the genuine source table; every later sheet is
     * skipped and logged, even if it also has a movie-name column.
     */
    private void processXlsxFile(File xlsxFile, LegacyCsvDatabaseService db,
                                  ExchangeRateService exchangeRate, Stats totals) throws Exception {
        List<XlsxParser.NamedTable> sheets = new XlsxParser().parse(xlsxFile.getAbsolutePath());
        if (sheets.isEmpty()) {
            log("=== " + xlsxFile.getName() + " === (no sheet with a recognisable movie-name column — skipped)");
            return;
        }
        XlsxParser.NamedTable first = sheets.get(0);
        log("=== " + xlsxFile.getName() + " :: " + first.sheetName() + " ===");
        // false: never insert brand-new "hindi" rows from a general-purpose workbook — see
        // the class-level javadoc for why that's restricted to the Bollywood-specific CSVs.
        processTable(xlsxFile.getName() + ":" + first.sheetName(), first.data(), db, exchangeRate, totals, false);

        for (int i = 1; i < sheets.size(); i++) {
            log("  Skipping sheet '" + sheets.get(i).sheetName() + "' — treating it as a derived/" +
                "cleaned copy of '" + first.sheetName() + "', not additional source data.");
        }
    }

    /**
     * @param trustedSource true only for a file on {@code legacycsv.insert.new.movies.files}.
     *                       A trusted source may both UPDATE an existing unique match and
     *                       INSERT a brand-new row for no match at all. Any other source is
     *                       report-only: matches/unmatched/ambiguous are still counted (so the
     *                       summary shows what coverage would look like), but nothing is ever
     *                       written to the DB — matching by title alone against a dataset that
     *                       isn't verified to be Hindi/Bollywood-specific is exactly how an
     *                       unrelated same-titled movie's real facts (budget, runtime, rating)
     *                       ended up overwriting the wrong movie's row (confirmed twice: a
     *                       Bollywood "Fight Club" picking up the 1999 Hollywood film's numbers,
     *                       and ~50 more titles like "Cinderella"/"Home Alone" from the TMDb
     *                       xlsx colliding with unrelated same-titled "Hindi" DB rows).
     */
    private void processTable(String sourceLabel, CsvData csv, LegacyCsvDatabaseService db,
                               ExchangeRateService exchangeRate, Stats totals,
                               boolean trustedSource) throws Exception {
        HeaderMap headerMap = mapHeaders(csv.headers());
        Map<Field, String> headerByField = headerMap.headers();
        Map<Field, Currency> currencyByField = headerMap.currencies();
        log(String.format("  Revenue column: %s | Budget column: %s",
            describeMoneyColumn(headerByField, currencyByField, Field.REVENUE),
            describeMoneyColumn(headerByField, currencyByField, Field.BUDGET)));

        if (!headerByField.containsKey(Field.MOVIE_NAME)) {
            log("  Skipping " + sourceLabel + " — no recognisable movie-name column found.");
            return;
        }

        for (Map<String, String> row : csv.rows()) {
            totals.rows++;
            String movieName = value(row, headerByField, Field.MOVIE_NAME);
            if (movieName == null || movieName.isBlank()) {
                totals.unmatched++;
                continue;
            }

            LegacyCsvDatabaseService.MatchResult match = db.findUniqueHindiMatch(movieName.trim());
            if (match.isAmbiguous()) {
                totals.ambiguous++;
                continue;
            }

            String genre             = normalizeGenre(value(row, headerByField, Field.GENRE));
            String releaseEventType  = normalizeBlank(value(row, headerByField, Field.RELEASE_PERIOD));
            Integer screens          = parseInt(value(row, headerByField, Field.SCREENS));
            Integer runtimeMinutes   = parseInt(value(row, headerByField, Field.RUNTIME));
            BigDecimal rating10      = parseAmount(value(row, headerByField, Field.RATING));
            BigDecimal revenueRaw    = parseAmount(value(row, headerByField, Field.REVENUE));
            BigDecimal budgetRaw     = parseAmount(value(row, headerByField, Field.BUDGET));
            Currency revenueCurrency = currencyByField.getOrDefault(Field.REVENUE, Currency.INR);
            Currency budgetCurrency  = currencyByField.getOrDefault(Field.BUDGET, Currency.INR);

            if (match.isUnique()) {
                if (!trustedSource) {
                    // Would be a match, but this source isn't vetted as Hindi-specific — don't
                    // risk writing an unrelated same-titled movie's data onto this row. Counted
                    // separately from "updated" so the summary doesn't imply a write happened.
                    totals.matchedButNotTrusted++;
                    continue;
                }
                LegacyCsvDatabaseService.ExistingRow existing = match.row();

                boolean needsRate = (revenueRaw != null && revenueCurrency == Currency.INR)
                                 || (budgetRaw != null && budgetCurrency == Currency.INR);
                Double inrToUsdRate = null;
                if (needsRate) {
                    try {
                        inrToUsdRate = exchangeRate.getInrToUsdRate(historicalRateDate(existing.releaseDate()));
                    } catch (IOException | InterruptedException e) {
                        // Currency lookup failed for this one movie (network hiccup, xe.com gap
                        // for that date, etc.) — skip just the money fields rather than aborting
                        // the whole batch; genre/release_event_type/screens below are unaffected.
                        log("  Exchange-rate lookup failed for '" + movieName + "' (" +
                            existing.releaseDate() + "): " + e.getMessage() + " — skipping revenue/budget for this row.");
                        totals.rateLookupFailed++;
                    }
                }

                Long revenueUsd = toUsd(revenueRaw, revenueCurrency, inrToUsdRate, exchangeRate);
                Long budgetUsd  = toUsd(budgetRaw, budgetCurrency, inrToUsdRate, exchangeRate);

                if (genre == null && releaseEventType == null && screens == null
                        && revenueUsd == null && budgetUsd == null
                        && runtimeMinutes == null && rating10 == null) {
                    totals.noFields++;
                    continue;
                }

                db.fillMissingFields(existing.movieName(), existing.releaseDate(),
                    genre, releaseEventType, revenueUsd, budgetUsd, screens, runtimeMinutes, rating10);
                totals.updated++;
                continue;
            }

            // No existing row at all (match count 0). Only a trusted (allowlisted) source
            // inserts a brand-new movie for this — see this method's javadoc for why.
            if (!trustedSource) {
                totals.unmatched++;
                continue;
            }

            // No release_date is known for a brand-new row, so an INR figure can't be
            // rate-converted (no year to pick a historical exchange rate against) — only a
            // USD-denominated amount can be trusted here.
            Long revenueUsd = revenueCurrency == Currency.USD ? toUsd(revenueRaw, Currency.USD, null, exchangeRate) : null;
            Long budgetUsd  = budgetCurrency == Currency.USD ? toUsd(budgetRaw, Currency.USD, null, exchangeRate) : null;
            if ((revenueRaw != null && revenueCurrency == Currency.INR) ||
                (budgetRaw != null && budgetCurrency == Currency.INR)) {
                totals.moneyDroppedOnInsert++;
            }

            boolean inserted = db.insertNewRow(movieName.trim(), genre, releaseEventType,
                revenueUsd, budgetUsd, screens, runtimeMinutes, rating10);
            // A duplicate title within/across source files (e.g. two identical CSVs, or the
            // same movie listed twice) lands on the same (movie_name, "Unknown", "hindi") PK
            // and is a no-op the second time, since findUniqueHindiMatch never matches an
            // "Unknown" row for a later fill — count that as unmatched, not a fresh insert.
            if (inserted) totals.inserted++; else totals.unmatched++;
        }
    }

    /**
     * Converts one raw money amount to whole USD according to its detected currency.
     * INR amounts require a resolved exchange rate (null when the lookup failed for this
     * row, in which case the amount is dropped rather than stored unconverted/wrong).
     */
    private Long toUsd(BigDecimal amount, Currency currency, Double inrToUsdRate, ExchangeRateService exchangeRate) {
        if (amount == null) return null;
        if (currency == Currency.USD) {
            return Math.round(amount.doubleValue());
        }
        if (inrToUsdRate == null) return null;
        return exchangeRate.inrCroreToUsd(amount.doubleValue() / 10_000_000.0, inrToUsdRate);
    }

    private String describeMoneyColumn(Map<Field, String> headerByField,
                                        Map<Field, Currency> currencyByField, Field field) {
        String header = headerByField.get(field);
        if (header == null) return "(none)";
        return "'" + header + "' [" + currencyByField.getOrDefault(field, Currency.INR) + "]";
    }

    /**
     * Pads a bare-year release_date (e.g. "2007", the format some crawler-inserted rows carry)
     * out to a mid-year date, so the xe.com lookup resolves to a real historical rate for that
     * movie's release year instead of silently falling back to today's rate (ExchangeRateService
     * only recognises a full YYYY-MM-DD... prefix — anything shorter defaults to "now").
     * Full dates are passed through unchanged.
     */
    private String historicalRateDate(String releaseDate) {
        if (releaseDate != null && releaseDate.matches("\\d{4}")) {
            return releaseDate + "-06-30";
        }
        return releaseDate;
    }

    // ---- header mapping ----

    private HeaderMap mapHeaders(List<String> headers) {
        Map<Field, String> result = new LinkedHashMap<>();
        Map<Field, Currency> currencies = new LinkedHashMap<>();
        for (String header : headers) {
            String norm = normalizeHeader(header);
            Field field = switch (norm) {
                case "movie name", "movie", "film", "title" -> Field.MOVIE_NAME;
                case "release period" -> Field.RELEASE_PERIOD;
                case "genre" -> Field.GENRE;
                case "number of screens", "screens" -> Field.SCREENS;
                case "runtime min", "runtime minutes", "runtime" -> Field.RUNTIME;
                case "imdb rating", "rating" -> Field.RATING;
                default -> norm.contains("revenue") ? Field.REVENUE
                         : norm.contains("budget")  ? Field.BUDGET
                         : null;
            };
            if (field == null) continue;
            boolean firstSeen = result.putIfAbsent(field, header) == null;
            if (firstSeen && (field == Field.REVENUE || field == Field.BUDGET)) {
                // Currency is read off the header itself (e.g. "Revenue(INR)" vs
                // "Revenue(USD)"); no marker at all defaults to INR, this dataset's convention.
                currencies.put(field, norm.contains("usd") ? Currency.USD : Currency.INR);
            }
        }
        return new HeaderMap(result, currencies);
    }

    private String normalizeHeader(String header) {
        return header.toLowerCase().trim().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String value(Map<String, String> row, Map<Field, String> headerByField, Field field) {
        String header = headerByField.get(field);
        if (header == null) return null;
        String v = row.get(header);
        return v == null ? null : v.trim();
    }

    // ---- value sanitisation ----

    /** Title-cases a genre value, turning "love_story"/"rom__com" into "Love Story"/"Rom Com". */
    private String normalizeGenre(String raw) {
        String cleaned = normalizeBlank(raw);
        if (cleaned == null) return null;
        String[] words = cleaned.replaceAll("_+", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String normalizeBlank(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return (trimmed.isEmpty() || "-".equals(trimmed)) ? null : trimmed;
    }

    /** Parses a whole-number field. Tolerates decimal-formatted spreadsheet cells (e.g. "142.0"). */
    private Integer parseInt(String raw) {
        String v = normalizeBlank(raw);
        if (v == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(v.replace(",", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseAmount(String raw) {
        String v = normalizeBlank(raw);
        if (v == null) return null;
        try {
            BigDecimal amount = new BigDecimal(v.replace(",", ""));
            return amount.signum() > 0 ? amount : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- plumbing ----

    private static final class Stats {
        long rows, updated, inserted, matchedButNotTrusted, unmatched, ambiguous, noFields,
             rateLookupFailed, moneyDroppedOnInsert;
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
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
