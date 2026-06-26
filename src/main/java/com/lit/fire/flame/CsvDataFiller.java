package com.lit.fire.flame;

import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;
import com.lit.fire.flame.db.DatabaseService;
import com.lit.fire.flame.enrichment.EnrichmentResult;
import com.lit.fire.flame.enrichment.EnrichmentService;
import com.lit.fire.flame.mapper.ColumnMapper;

import java.io.IOException;
import java.io.InputStream;
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
            System.out.printf("Running fuzzy duplicate check (auto-merge ≥ %.2f | warn ≥ %.2f)...%n",
                autoMergeThreshold, warnThreshold);
            db.ensureFuzzyIndex();

            String movieNameHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.MOVIE_NAME_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            String releaseDateHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.RELEASE_DATE_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);

            List<String[]> pairs = extractUniqueNameDatePairs(csvData, movieNameHeader, releaseDateHeader, mapper);
            System.out.printf("Checking %,d unique (movie, release_date) pairs against existing data...%n",
                pairs.size());

            List<String> warnings = new ArrayList<>();
            Map<String, String> autoMergeMap =
                db.findFuzzyMatches(pairs, warnThreshold, autoMergeThreshold, warnings);

            System.out.printf("Fuzzy check done — auto-merges: %d | potential duplicates: %d%n",
                autoMergeMap.size(), warnings.size());

            if (!warnings.isEmpty()) {
                System.out.println();
                System.out.println("Potential duplicates (not auto-merged — review manually):");
                warnings.forEach(System.out::println);
            }
            System.out.println();

            System.out.println("Starting import...");
            db.batchUpsert(csvData, csvToDb, existingCols, autoMergeMap);

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
