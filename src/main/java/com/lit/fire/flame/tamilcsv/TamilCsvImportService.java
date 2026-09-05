package com.lit.fire.flame.tamilcsv;

import com.lit.fire.flame.crawler.ExchangeRateService;
import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-shot importer that fills pre-existing columns in movies_data_collection (genre, runtime,
 * budget, revenue, rating_10, directors, production_companies, release_day) from Tamil-movie
 * CSVs named in {@code tamilcsv.files} — see application.properties — and marks every row's
 * language as "tamil" explicitly (never inferred/defaulted).
 *
 * Unlike LegacyCsvBackfillService (the Hindi importer), this source carries a real Release_Date,
 * so rows are matched to the DB by (movie_name, release year, language='tamil') rather than
 * title alone — the same year-based convention CreditsDatabaseService/EconomicDatabaseService/
 * RuntimeBudgetDatabaseService already use. That's a deliberate safety choice: matching by
 * title alone (with no year to disambiguate) is exactly what let unrelated same-titled movies
 * from different files/languages overwrite each other's budget/runtime/rating earlier in this
 * project — seeing this file has a real date, there is no reason to accept that same risk here.
 * A row whose Release_Date can't be parsed is skipped entirely rather than falling back to a
 * placeholder date.
 *
 * Like the Hindi importer, only files explicitly named in {@code tamilcsv.files} are processed
 * at all (an explicit, hand-curated allowlist, not "every CSV in the folder") — see that
 * property's comment for why. A field already populated in the DB is never overwritten
 * (fill-if-missing only). Budget/Revenue are read from the "_Local_Currency" columns (assumed
 * INR unless the header says otherwise) and converted to whole USD via the shared xe.com/
 * currency_rate_xe rate cache, using the row's own real release date.
 */
public class TamilCsvImportService {

    private static final String PREFIX = "[TAMIL-CSV] ";
    private static final DateTimeFormatter SOURCE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");
        String folder     = config.getProperty("legacycsv.folder",
            "/Users/mukundv/Documents/work/space/new_data_collection");

        Set<String> trustedFiles = Arrays.stream(
                config.getProperty("tamilcsv.files", "tamil_movies_2015_2026.csv").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        File dir = new File(folder);
        File[] dataFiles = dir.listFiles((d, name) -> trustedFiles.contains(name.toLowerCase()));
        if (dataFiles == null || dataFiles.length == 0) {
            log("None of tamilcsv.files found in " + folder + " — nothing to do.");
            return;
        }

        ExchangeRateService exchangeRate = new ExchangeRateService();
        Stats totals = new Stats();

        try (TamilCsvDatabaseService db = new TamilCsvDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            db.ensureRateTableExists();
            db.ensureNameYearMatchIndex();
            Map<String, Double> existingRates = db.getExistingRates("INR", "USD");
            exchangeRate.preloadCache(existingRates);
            log(String.format("Pre-loaded %,d exchange rate(s) from currency_rate_xe.", existingRates.size()));

            for (File dataFile : dataFiles) {
                log("=== " + dataFile.getName() + " ===");
                CsvData csv = new CsvParser().parse(dataFile.getAbsolutePath());
                processTable(csv, db, exchangeRate, totals);
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
            "Done — rows: %,d | updated: %,d | inserted (new movies): %,d | unmatched: %,d | " +
            "ambiguous (skipped): %,d | bad/missing date (skipped): %,d | rate lookup failed: %,d",
            totals.rows, totals.updated, totals.inserted, totals.unmatched, totals.ambiguous,
            totals.badDate, totals.rateLookupFailed));
    }

    private void processTable(CsvData csv, TamilCsvDatabaseService db,
                               ExchangeRateService exchangeRate, Stats totals) throws Exception {
        if (!csv.headers().contains("Title") || !csv.headers().contains("Release_Date")) {
            log("  Skipping — expected columns 'Title' and 'Release_Date' not found.");
            return;
        }

        for (Map<String, String> row : csv.rows()) {
            totals.rows++;
            String movieName = trimToNull(row.get("Title"));
            if (movieName == null) {
                totals.unmatched++;
                continue;
            }

            String isoDate = parseSourceDate(row.get("Release_Date"));
            if (isoDate == null) {
                log("  Skipping '" + movieName + "' — unparseable Release_Date '" + row.get("Release_Date") + "'.");
                totals.badDate++;
                continue;
            }
            String year = isoDate.substring(0, 4);

            String genre               = normalizeGenre(row.get("Genres"));
            String director            = trimToNull(row.get("Director"));
            String productionCompany   = trimToNull(row.get("Production_Company"));
            String releaseDay          = trimToNull(row.get("Release_DayOfWeek"));
            Integer runtimeMinutes     = parseInt(row.get("Runtime_Minutes"));
            BigDecimal rating10        = parseAmount(row.get("Vote_Average"));
            BigDecimal budgetRaw       = parseAmount(row.get("Budget_Local_Currency"));
            BigDecimal revenueRaw      = parseAmount(row.get("Revenue_Local_Currency"));

            TamilCsvDatabaseService.MatchResult match = db.findUniqueMatch(movieName, year);
            if (match.isAmbiguous()) {
                totals.ambiguous++;
                continue;
            }

            Double inrToUsdRate = null;
            if (budgetRaw != null || revenueRaw != null) {
                String rateDate = match.isUnique() ? match.row().releaseDate() : isoDate;
                try {
                    inrToUsdRate = exchangeRate.getInrToUsdRate(rateDate);
                } catch (IOException | InterruptedException e) {
                    log("  Exchange-rate lookup failed for '" + movieName + "' (" + rateDate + "): " +
                        e.getMessage() + " — skipping budget/revenue for this row.");
                    totals.rateLookupFailed++;
                }
            }
            Long budgetUsd  = toUsd(budgetRaw, inrToUsdRate, exchangeRate);
            Long revenueUsd = toUsd(revenueRaw, inrToUsdRate, exchangeRate);

            if (match.isUnique()) {
                db.fillMissingFields(match.row().movieName(), match.row().releaseDate(),
                    genre, runtimeMinutes, budgetUsd, revenueUsd, rating10,
                    director, productionCompany, releaseDay);
                totals.updated++;
                continue;
            }

            boolean inserted = db.insertNewRow(movieName, isoDate, genre, runtimeMinutes,
                budgetUsd, revenueUsd, rating10, director, productionCompany, releaseDay);
            if (inserted) totals.inserted++; else totals.unmatched++;
        }
    }

    // ---- parsing helpers ----

    /** Parses the source's "dd-MM-yyyy" format into ISO "yyyy-MM-dd"; null if unparseable. */
    private String parseSourceDate(String raw) {
        String v = trimToNull(raw);
        if (v == null) return null;
        try {
            return LocalDate.parse(v, SOURCE_DATE_FORMAT).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long toUsd(BigDecimal amountInr, Double inrToUsdRate, ExchangeRateService exchangeRate) {
        if (amountInr == null || inrToUsdRate == null) return null;
        return exchangeRate.inrCroreToUsd(amountInr.doubleValue() / 10_000_000.0, inrToUsdRate);
    }

    /** Title-cases nothing — Genres arrives already well-formatted ("Action, Drama"); just trims/blanks. */
    private String normalizeGenre(String raw) {
        return trimToNull(raw);
    }

    private String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return (trimmed.isEmpty() || "-".equals(trimmed)) ? null : trimmed;
    }

    private Integer parseInt(String raw) {
        String v = trimToNull(raw);
        if (v == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(v.replace(",", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseAmount(String raw) {
        String v = trimToNull(raw);
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
        long rows, updated, inserted, unmatched, ambiguous, badDate, rateLookupFailed;
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
