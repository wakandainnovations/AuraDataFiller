package com.lit.fire.flame;

import com.lit.fire.flame.crawler.CrawlerDatabaseService;
import com.lit.fire.flame.crawler.ExchangeRateService;
import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;
import com.lit.fire.flame.db.DatabaseService;
import com.lit.fire.flame.description.DescriptionParser;
import com.lit.fire.flame.enrichment.EnrichmentResult;
import com.lit.fire.flame.enrichment.EnrichmentService;
import com.lit.fire.flame.mapper.ColumnMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class CsvDataFiller {

    private static final String DEFAULT_TABLE = "movies";

    public void process(String filePath) {
        Properties secrets   = loadProperties("secrets.properties", true);
        Properties appConfig = loadProperties("application.properties", false);
        String tableName = appConfig.getProperty("table.name", DEFAULT_TABLE);

        double autoMergeThreshold = Double.parseDouble(
            appConfig.getProperty("fuzzy.automerge.threshold", "0.93"));
        double warnThreshold = Double.parseDouble(
            appConfig.getProperty("fuzzy.warn.threshold", "0.75"));
        int fuzzyMaxPairs = Integer.parseInt(
            appConfig.getProperty("fuzzy.max.pairs", "50000"));
        boolean descriptionFinanceEnabled = Boolean.parseBoolean(
            appConfig.getProperty("description.finance.enrichment.enabled", "true"));

        System.out.println("=== AuraDataFiller ===");
        System.out.println("Source : " + filePath);
        System.out.println("Table  : " + tableName);
        System.out.println();

        CsvData csvData;
        try {
            System.out.println("Parsing CSV...");
            csvData = new CsvParser().parse(filePath);
            System.out.printf("Loaded %,d rows | %d columns: %s%n%n",
                csvData.rows().size(), csvData.headers().size(), csvData.headers());
        } catch (IOException e) {
            throw new RuntimeException("Cannot read CSV file: " + filePath, e);
        }

        ColumnMapper mapper = new ColumnMapper();

        // Transform rows: format release_date, derive release_day, expand language codes
        csvData = transformRows(csvData, mapper);

        // Optional: enrich rows with GDP, inflation, box office, and event data
        boolean enrichmentEnabled = Boolean.parseBoolean(
            appConfig.getProperty("enrichment.enabled", "false"));
        if (enrichmentEnabled) {
            System.out.println("Enriching rows with external data...");
            EnrichmentService enrichmentService = createEnrichmentService(secrets, appConfig);
            csvData = enrichRows(csvData, mapper, enrichmentService);
            System.out.println();
        }

        // Build csvToDb, skipping columns that should be ignored (e.g. 'overview')
        Map<String, String> csvToDb = new LinkedHashMap<>();
        for (String header : csvData.headers()) {
            if (!mapper.shouldSkipCsvHeader(header)) {
                csvToDb.put(header, mapper.toDbColumnName(header));
            }
        }

        System.out.println("Column mapping:");
        csvToDb.forEach((csv, db) -> System.out.printf("  %-30s → %s%n", csv, db));
        System.out.println();

        String url      = secrets.getProperty("db.url");
        String user     = secrets.getProperty("db.user");
        String password = secrets.getProperty("db.password", "");

        try (DatabaseService db = new DatabaseService(url, user, password, tableName, mapper)) {

            System.out.println("Ensuring table exists...");
            db.ensureTableExists();

            Map<String, String> existingCols = db.getExistingColumns();
            System.out.println("Existing columns: " + existingCols.keySet());
            System.out.println();

            System.out.println("Checking for new columns...");
            for (Map.Entry<String, String> entry : csvToDb.entrySet()) {
                String csvHeader = entry.getKey();
                String dbCol     = entry.getValue();
                if (mapper.isPkColumn(dbCol) || existingCols.containsKey(dbCol)) continue;

                List<String> colValues = csvData.rows().stream()
                    .map(row -> row.get(csvHeader))
                    .collect(Collectors.toList());
                ColumnMapper.ColumnType type = mapper.getKnownColumnType(dbCol)
                    .orElseGet(() -> mapper.inferType(colValues));
                db.addColumn(dbCol, type);
                existingCols.put(dbCol, type == ColumnMapper.ColumnType.NUMERIC ? "numeric" : "text");
            }
            System.out.println();

            // Fuzzy duplicate pre-check
            db.ensureFuzzyIndex();

            String movieNameHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.MOVIE_NAME_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            String releaseDateHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.RELEASE_DATE_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);

            List<String[]> pairs = extractUniqueNameDatePairs(csvData, movieNameHeader, releaseDateHeader, mapper);

            List<String> warnings = new ArrayList<>();
            Map<String, String> autoMergeMap;
            if (pairs.size() > fuzzyMaxPairs) {
                System.out.printf(
                    "Skipping fuzzy check — %,d unique pairs exceeds limit of %,d (too large to check efficiently).%n",
                    pairs.size(), fuzzyMaxPairs);
                autoMergeMap = Map.of();
            } else {
                System.out.printf("Running fuzzy duplicate check (auto-merge ≥ %.2f | warn ≥ %.2f)...%n",
                    autoMergeThreshold, warnThreshold);
                System.out.printf("Checking %,d unique (movie, release_date) pairs against existing data...%n",
                    pairs.size());
                autoMergeMap = db.findFuzzyMatches(pairs, warnThreshold, autoMergeThreshold, warnings);
                System.out.printf("Fuzzy check done — auto-merges: %d | potential duplicates: %d%n",
                    autoMergeMap.size(), warnings.size());
                if (!warnings.isEmpty()) {
                    System.out.println();
                    System.out.println("Potential duplicates (not auto-merged — review manually):");
                    warnings.forEach(System.out::println);
                }
            }
            System.out.println();

            System.out.println("Starting import...");
            db.batchUpsert(csvData, csvToDb, existingCols, autoMergeMap);

            if (descriptionFinanceEnabled) {
                enrichFinanceFromDescriptions(csvData, mapper, db, autoMergeMap,
                    movieNameHeader, releaseDateHeader, secrets, tableName);
            }

        } catch (Exception e) {
            System.err.println("Import failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Applies pre-DB transformations to CSV rows:
     *   - release_date: converts YYYY-MM-DD → YYYYMMDD and derives the day name into release_day
     *   - original_language: expands 2-char ISO code to full language name
     */
    private CsvData transformRows(CsvData csvData, ColumnMapper mapper) {
        boolean hasReleaseDate      = csvData.headers().contains("release_date");
        boolean hasOriginalLanguage = csvData.headers().contains("original_language");

        List<String> headers = new ArrayList<>(csvData.headers());
        if (hasReleaseDate && !headers.contains(ColumnMapper.RELEASE_DAY_COL)) {
            headers.add(ColumnMapper.RELEASE_DAY_COL);
        }

        for (Map<String, String> row : csvData.rows()) {
            if (hasReleaseDate) {
                String originalDate = row.get("release_date");
                if (originalDate != null && !originalDate.isBlank()) {
                    String trimmed = originalDate.trim();
                    row.put("release_date", trimmed);
                    try {
                        LocalDate date = LocalDate.parse(trimmed);
                        row.put(ColumnMapper.RELEASE_DAY_COL,
                            date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                    } catch (DateTimeParseException ex) {
                        row.put(ColumnMapper.RELEASE_DAY_COL, null);
                    }
                }
            }
            if (hasOriginalLanguage) {
                String langCode = row.get("original_language");
                row.put("original_language", mapper.expandLanguageCode(langCode));
            }
        }

        return new CsvData(List.copyOf(headers), csvData.rows());
    }

    /**
     * Adds enrichment columns (GDP, inflation, box office, event info) to every row.
     * The enrichment columns are appended to the header list and populated in each row map.
     * Columns that already exist in the headers are skipped to avoid duplicates.
     */
    private CsvData enrichRows(CsvData csvData, ColumnMapper mapper, EnrichmentService enrichmentService) {
        // Locate the CSV headers for the fields we need
        String releaseDateHeader = null, languageHeader = null, countryHeader = null;

        for (String h : csvData.headers()) {
            String dbCol = mapper.toDbColumnName(h);
            if (ColumnMapper.RELEASE_DATE_COL.equals(dbCol) && releaseDateHeader == null) releaseDateHeader = h;
            if ("language".equals(dbCol)                    && languageHeader == null)    languageHeader   = h;
            // Accept "country", "production_countries", or "country_of_origin"
            if ((h.toLowerCase().contains("country") || h.equalsIgnoreCase("production_countries"))
                    && countryHeader == null) {
                countryHeader = h;
            }
        }

        // Append enrichment headers that are not already present
        List<String> headers = new ArrayList<>(csvData.headers());
        List<String> enrichCols = List.of(
            ColumnMapper.GDP_COL,      ColumnMapper.INFLATION_COL,
            ColumnMapper.EVENT_TYPE_COL, ColumnMapper.EVENT_NAME_COL,
            ColumnMapper.EVENT_DETAIL_COL
        );
        for (String col : enrichCols) {
            if (!headers.contains(col)) headers.add(col);
        }

        long total = csvData.rows().size();
        long count = 0;
        for (Map<String, String> row : csvData.rows()) {
            String releaseDate = releaseDateHeader != null ? row.get(releaseDateHeader) : null;
            String language    = languageHeader    != null ? row.get(languageHeader)    : null;
            String country     = countryHeader     != null ? row.get(countryHeader)     : null;

            EnrichmentResult r = enrichmentService.enrich(releaseDate, language, country);

            row.put(ColumnMapper.GDP_COL,       r.gdpBillionsUsd()   != null ? r.gdpBillionsUsd().toString()   : null);
            row.put(ColumnMapper.INFLATION_COL, r.inflationRatePct() != null ? r.inflationRatePct().toString() : null);
            row.put(ColumnMapper.EVENT_TYPE_COL,   r.releaseEventType());
            row.put(ColumnMapper.EVENT_NAME_COL,   r.releaseEventName());
            row.put(ColumnMapper.EVENT_DETAIL_COL, r.releaseEventDetail());

            count++;
            if (count % 50 == 0 || count == total) {
                System.out.printf("  Enriched %,d / %,d rows...%n", count, total);
            }
        }

        return new CsvData(List.copyOf(headers), csvData.rows());
    }

    /**
     * Parses the free-text "Description" column (IMDb list exports pack budget / worldwide
     * gross / verdict etc. into it, e.g. "Budget : 300crWorldwide gross : 1152cr...") and
     * updates the matching DB row(s) via {@link DatabaseService#updateFinanceGreatest}.
     *
     * Matching is by movie_name + release year (not the exact PK), so it reaches rows
     * inserted by other sources (e.g. the sacnilk crawler) regardless of language/date-format
     * differences — same convention {@code CrawlerDatabaseService} already uses.
     * INR crore figures are converted to USD using the exchange rate on the movie's release
     * date (or 1 July of its release year when only the year is known); figures already in
     * USD (a bare "$..." or an accompanying "US$..."/"...USD" figure) are used as-is.
     */
    private void enrichFinanceFromDescriptions(CsvData csvData, ColumnMapper mapper, DatabaseService db,
                                                Map<String, String> autoMergeMap,
                                                String movieNameHeader, String releaseDateHeader,
                                                Properties secrets, String tableName) throws Exception {
        String descHeader = findHeaderIgnoreCase(csvData.headers(), "description");
        if (descHeader == null || movieNameHeader == null) return;

        String yearHeader     = findHeaderIgnoreCase(csvData.headers(), "year");
        String fullDateHeader = findHeaderIgnoreCase(csvData.headers(), "release date");

        System.out.println("Parsing box-office figures out of the Description column...");
        db.ensureFinanceColumns();

        DescriptionParser parser = new DescriptionParser();
        String url      = secrets.getProperty("db.url");
        String user     = secrets.getProperty("db.user");
        String password = secrets.getProperty("db.password", "");

        long updated = 0, noData = 0, noMatch = 0, errors = 0;

        try (CrawlerDatabaseService rateDb = new CrawlerDatabaseService(url, user, password, tableName)) {
            rateDb.ensureRateTableExists();
            ExchangeRateService exchangeRate = new ExchangeRateService();
            exchangeRate.preloadCache(rateDb.getExistingRates("INR", "USD"));

            for (Map<String, String> row : csvData.rows()) {
                String desc = row.get(descHeader);
                if (desc == null || desc.isBlank()) { noData++; continue; }

                String movieName = mapper.sanitizeValue(row.get(movieNameHeader), "text");
                if (movieName == null) { noData++; continue; }

                String year = yearHeader != null ? extractYear(row.get(yearHeader)) : null;
                if (year == null && fullDateHeader != null) year = extractYear(row.get(fullDateHeader));
                if (year == null) { noData++; continue; }

                String fullDate = fullDateHeader != null ? row.get(fullDateHeader) : null;
                String rateDate = isValidIsoDate(fullDate) ? fullDate.trim().substring(0, 10) : (year + "-07-01");

                // Apply the same fuzzy-merge normalisation batchUpsert used, so we update the
                // exact row(s) the CSV import just touched.
                String mergeDateKey = releaseDateHeader != null
                    ? mapper.sanitizeValue(row.get(releaseDateHeader), "text") : year;
                if (mergeDateKey != null) {
                    String normalised = autoMergeMap.get(DatabaseService.mergeKey(movieName, mergeDateKey));
                    if (normalised != null) movieName = normalised;
                }

                DescriptionParser.ParsedDescription parsed = parser.parse(desc);
                if (parsed.amounts().isEmpty() && parsed.verdict() == null && parsed.note() == null) {
                    noData++;
                    continue;
                }

                Map<DescriptionParser.Field, Long> usdAmounts = new EnumMap<>(DescriptionParser.Field.class);
                Double rate = null;
                boolean rateFetchFailed = false;

                for (Map.Entry<DescriptionParser.Field, DescriptionParser.Amount> e : parsed.amounts().entrySet()) {
                    DescriptionParser.Amount amount = e.getValue();
                    if (amount.isUsd()) {
                        usdAmounts.put(e.getKey(), amount.value().setScale(0, RoundingMode.HALF_UP).longValueExact());
                        continue;
                    }
                    if (rate == null && !rateFetchFailed) {
                        try {
                            rate = exchangeRate.getInrToUsdRate(rateDate);
                        } catch (Exception ex) {
                            rateFetchFailed = true;
                            System.err.printf("  Warning: exchange rate lookup failed for %s ('%s'): %s%n",
                                rateDate, movieName, ex.getMessage());
                        }
                    }
                    if (rate != null) {
                        usdAmounts.put(e.getKey(), exchangeRate.inrCroreToUsd(amount.value().doubleValue(), rate));
                    }
                }

                if (usdAmounts.isEmpty() && parsed.verdict() == null && parsed.note() == null) {
                    noData++;
                    continue;
                }

                try {
                    int rows = db.updateFinanceGreatest(movieName, year, usdAmounts, parsed.verdict(), parsed.note());
                    if (rows > 0) updated++; else noMatch++;
                } catch (Exception ex) {
                    errors++;
                    System.err.printf("  Warning: finance update failed for '%s' (%s): %s%n",
                        movieName, year, ex.getMessage());
                }
            }

            for (Map.Entry<String, Double> e : exchangeRate.getNewlyFetchedRates().entrySet()) {
                rateDb.upsertExchangeRate(e.getKey(), "INR", "USD", e.getValue());
            }
        }

        System.out.printf(
            "Description finance enrichment done — updated: %,d | no box-office data: %,d | no DB match: %,d | errors: %,d%n%n",
            updated, noData, noMatch, errors);
    }

    private String findHeaderIgnoreCase(List<String> headers, String target) {
        return headers.stream().filter(h -> h.equalsIgnoreCase(target)).findFirst().orElse(null);
    }

    /** Returns the leading 4-digit year from a value like "2016" or "2016-09-29", else null. */
    private String extractYear(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() < 4) return null;
        String candidate = trimmed.substring(0, 4);
        return candidate.matches("\\d{4}") ? candidate : null;
    }

    private boolean isValidIsoDate(String value) {
        if (value == null || value.trim().length() < 10) return false;
        try {
            LocalDate.parse(value.trim().substring(0, 10));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private EnrichmentService createEnrichmentService(Properties secrets, Properties appConfig) {
        boolean worldBankEnabled = Boolean.parseBoolean(
            appConfig.getProperty("enrichment.worldbank.enabled", "true"));
        boolean claudeEnabled = Boolean.parseBoolean(
            appConfig.getProperty("enrichment.claude.enabled", "true"));
        long delayMs = Long.parseLong(
            appConfig.getProperty("enrichment.api.delay.ms", "300"));

        String claudeApiKey = secrets.getProperty("anthropic.api.key", "").trim();

        return new EnrichmentService(
            worldBankEnabled, claudeEnabled,
            claudeApiKey.isEmpty() ? null : claudeApiKey,
            delayMs
        );
    }

    /**
     * Collects unique (movie_name, release_date) pairs from the CSV that have valid, non-null
     * values for both PK fields. Used to drive the fuzzy duplicate pre-check.
     */
    private List<String[]> extractUniqueNameDatePairs(CsvData csvData,
                                                       String movieNameHeader,
                                                       String releaseDateHeader,
                                                       ColumnMapper mapper) {
        if (movieNameHeader == null || releaseDateHeader == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, String> row : csvData.rows()) {
            String name = mapper.sanitizeValue(row.get(movieNameHeader), "text");
            String date = mapper.sanitizeValue(row.get(releaseDateHeader), "text");
            if (name == null || date == null) continue;
            if (seen.add(name.toLowerCase() + "|" + date)) {
                pairs.add(new String[]{name, date});
            }
        }
        return pairs;
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
