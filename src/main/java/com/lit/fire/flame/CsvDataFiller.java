package com.lit.fire.flame;

import com.lit.fire.flame.csv.CsvData;
import com.lit.fire.flame.csv.CsvParser;
import com.lit.fire.flame.db.DatabaseService;
import com.lit.fire.flame.mapper.ColumnMapper;

import java.io.IOException;
import java.io.InputStream;
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

        Map<String, String> csvToDb = new LinkedHashMap<>();
        for (String header : csvData.headers()) {
            csvToDb.put(header, mapper.toDbColumnName(header));
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
                ColumnMapper.ColumnType type = mapper.inferType(colValues);
                db.addColumn(dbCol, type);
                existingCols.put(dbCol, type == ColumnMapper.ColumnType.NUMERIC ? "numeric" : "text");
            }
            System.out.println();

            // Fuzzy duplicate pre-check
            System.out.printf("Running fuzzy duplicate check (auto-merge ≥ %.2f | warn ≥ %.2f)...%n",
                autoMergeThreshold, warnThreshold);
            db.ensureFuzzyIndex();

            // Reverse lookup to find which CSV headers carry movie_name and year
            String movieNameHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.MOVIE_NAME_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            String yearHeader = csvToDb.entrySet().stream()
                .filter(e -> ColumnMapper.YEAR_COL.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);

            List<String[]> pairs = extractUniqueNameYearPairs(csvData, movieNameHeader, yearHeader, mapper);
            System.out.printf("Checking %,d unique (movie, year) pairs against existing data...%n",
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
     * Collects unique (movie_name, year) pairs from the CSV that have valid, non-null values
     * for both PK fields. Used to drive the fuzzy duplicate pre-check.
     */
    private List<String[]> extractUniqueNameYearPairs(CsvData csvData,
                                                       String movieNameHeader,
                                                       String yearHeader,
                                                       ColumnMapper mapper) {
        if (movieNameHeader == null || yearHeader == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> pairs = new ArrayList<>();
        for (Map<String, String> row : csvData.rows()) {
            String name = mapper.sanitizeValue(row.get(movieNameHeader), "text");
            String year = mapper.sanitizeValue(row.get(yearHeader), "text");
            if (name == null || year == null) continue;
            if (seen.add(name.toLowerCase() + "|" + year)) {
                pairs.add(new String[]{name, year});
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
