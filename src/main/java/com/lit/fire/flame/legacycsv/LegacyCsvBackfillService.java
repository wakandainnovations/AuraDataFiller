package com.lit.fire.flame.legacycsv;

import com.lit.fire.flame.crawler.ExchangeRateService;
import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;

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

/**
 * One-shot backfill that fills pre-existing columns in movies_data_collection (genre,
 * release_event_type, revenue, budget, number_of_screens) from historical Bollywood CSVs
 * dropped in {@code legacycsv.folder} — see application.properties.
 *
 * These CSVs carry no release_date/language, only a movie title, so rows are matched to the
 * DB by (movie_name, language='hindi') and only when that match is unique; ambiguous or
 * unmatched titles are skipped and counted in the summary rather than guessed at. No new
 * columns are ever created — every target field above already exists in the table — and a
 * field already populated in the DB is never overwritten (fill-if-missing only, same
 * convention as CreditsDatabaseService/RuntimeBudgetDatabaseService).
 *
 * Revenue/budget columns may be denominated in either INR or USD depending on the source
 * file — the currency is read off the header itself (e.g. "Revenue(INR)" vs "Revenue(USD)").
 * INR figures are converted to whole USD via the same xe.com historical-rate lookup (keyed
 * off the matched row's own release_date, and cached in currency_rate_xe) used everywhere
 * else in this codebase, so the stored values stay consistent with revenue/budget populated
 * by the box-office crawlers; USD figures are stored as-is. A header with no currency marker
 * is assumed to be INR (this dataset's historical convention).
 */
public class LegacyCsvBackfillService {

    private static final String PREFIX = "[LEGACY-CSV] ";

    /** Normalized CSV header → semantic field this importer understands. */
    private enum Field { MOVIE_NAME, RELEASE_PERIOD, GENRE, SCREENS, REVENUE, BUDGET }

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

        File dir = new File(folder);
        File[] csvFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            log("No CSV files found in " + folder + " — nothing to do.");
            return;
        }
        Arrays.sort(csvFiles, Comparator.comparing(File::getName));

        ExchangeRateService exchangeRate = new ExchangeRateService();
        Stats totals = new Stats();

        try (LegacyCsvDatabaseService db = new LegacyCsvDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            db.ensureRateTableExists();
            Map<String, Double> existingRates = db.getExistingRates("INR", "USD");
            exchangeRate.preloadCache(existingRates);
            log(String.format("Pre-loaded %,d exchange rate(s) from currency_rate_xe.", existingRates.size()));

            for (File csvFile : csvFiles) {
                log("=== " + csvFile.getName() + " ===");
                processFile(csvFile, db, exchangeRate, totals);
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
            "Done — rows: %,d | updated: %,d | unmatched: %,d | ambiguous (skipped): %,d | no usable fields: %,d | rate lookup failed: %,d",
            totals.rows, totals.updated, totals.unmatched, totals.ambiguous, totals.noFields, totals.rateLookupFailed));
    }

    private void processFile(File csvFile, LegacyCsvDatabaseService db,
                              ExchangeRateService exchangeRate, Stats totals) throws Exception {
        CsvData csv = new CsvParser().parse(csvFile.getAbsolutePath());
        HeaderMap headerMap = mapHeaders(csv.headers());
        Map<Field, String> headerByField = headerMap.headers();
        Map<Field, Currency> currencyByField = headerMap.currencies();
        log(String.format("  Revenue column: %s | Budget column: %s",
            describeMoneyColumn(headerByField, currencyByField, Field.REVENUE),
            describeMoneyColumn(headerByField, currencyByField, Field.BUDGET)));

        if (!headerByField.containsKey(Field.MOVIE_NAME)) {
            log("  Skipping file — no recognisable movie-name column found.");
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
            if (!match.isUnique()) {
                totals.unmatched++;
                continue;
            }
            LegacyCsvDatabaseService.ExistingRow existing = match.row();

            String genre             = normalizeGenre(value(row, headerByField, Field.GENRE));
            String releaseEventType  = normalizeBlank(value(row, headerByField, Field.RELEASE_PERIOD));
            Integer screens          = parseInt(value(row, headerByField, Field.SCREENS));
            BigDecimal revenueRaw    = parseAmount(value(row, headerByField, Field.REVENUE));
            BigDecimal budgetRaw     = parseAmount(value(row, headerByField, Field.BUDGET));
            Currency revenueCurrency = currencyByField.getOrDefault(Field.REVENUE, Currency.INR);
            Currency budgetCurrency  = currencyByField.getOrDefault(Field.BUDGET, Currency.INR);

            boolean needsRate = (revenueRaw != null && revenueCurrency == Currency.INR)
                             || (budgetRaw != null && budgetCurrency == Currency.INR);
            Double inrToUsdRate = null;
            if (needsRate) {
                try {
                    inrToUsdRate = exchangeRate.getInrToUsdRate(historicalRateDate(existing.releaseDate()));
                } catch (IOException | InterruptedException e) {
                    // Currency lookup failed for this one movie (network hiccup, xe.com gap for
                    // that date, etc.) — skip just the money fields rather than aborting the
                    // whole batch; genre/release_event_type/screens below are unaffected.
                    log("  Exchange-rate lookup failed for '" + movieName + "' (" +
                        existing.releaseDate() + "): " + e.getMessage() + " — skipping revenue/budget for this row.");
                    totals.rateLookupFailed++;
                }
            }

            Long revenueUsd = toUsd(revenueRaw, revenueCurrency, inrToUsdRate, exchangeRate);
            Long budgetUsd  = toUsd(budgetRaw, budgetCurrency, inrToUsdRate, exchangeRate);

            if (genre == null && releaseEventType == null && screens == null
                    && revenueUsd == null && budgetUsd == null) {
                totals.noFields++;
                continue;
            }

            db.fillMissingFields(existing.movieName(), existing.releaseDate(),
                genre, releaseEventType, revenueUsd, budgetUsd, screens);
            totals.updated++;
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

    private Integer parseInt(String raw) {
        String v = normalizeBlank(raw);
        if (v == null) return null;
        try {
            return Integer.valueOf(v.replace(",", ""));
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
        long rows, updated, unmatched, ambiguous, noFields, rateLookupFailed;
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
