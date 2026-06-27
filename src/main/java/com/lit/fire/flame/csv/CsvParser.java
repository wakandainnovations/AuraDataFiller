package com.lit.fire.flame.csv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CsvParser {

    public CsvData parse(String filePath) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Reader reader = new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .setTrim(true)
                 .setIgnoreEmptyLines(true)
                 .setAllowMissingColumnNames(true)
                 .build()
                 .parse(reader)) {

            // Drop blank-named columns (e.g. leading-comma index columns)
            List<String> headers = parser.getHeaderNames().stream()
                .filter(h -> h != null && !h.isBlank())
                .collect(java.util.stream.Collectors.toList());

            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : headers) {
                    row.put(header, record.get(header));
                }
                rows.add(row);
            }

            return new CsvData(List.copyOf(headers), rows);
        }
    }
}
