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

        // Map every CSV header to its DB column name (order preserved)
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

            System.out.println("Starting import...");
            db.batchUpsert(csvData, csvToDb, existingCols);

        } catch (Exception e) {
            System.err.println("Import failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
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
