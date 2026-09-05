package com.lit.fire.flame.csv;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads every sheet of an .xlsx workbook into a {@link CsvData} table each, the same shape
 * CsvParser produces for a plain CSV — so downstream header-mapping/import code can treat a
 * workbook sheet exactly like a CSV file.
 *
 * Real-world analysis workbooks (e.g. one exported alongside pandas/Excel statistics tabs)
 * often have a title row and/or a blank spacer row above the actual header, and carry several
 * non-data sheets (summary stats, notes). Rather than assuming row 1 is the header, each
 * sheet is scanned for the first row containing a recognisable movie-name column; sheets
 * where no such row is found within the first 20 rows are treated as non-data and skipped.
 */
public class XlsxParser {

    public record NamedTable(String sheetName, CsvData data) {}

    private static final Set<String> MOVIE_NAME_MARKERS = Set.of(
        "movie name", "movie", "film", "title"
    );

    private static final int MAX_HEADER_SCAN_ROWS = 20;

    /** Returns one NamedTable per sheet that has a detectable movie-name header row. */
    public List<NamedTable> parse(String filePath) throws IOException {
        List<NamedTable> tables = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : workbook) {
                CsvData table = parseSheet(sheet, formatter);
                if (table != null) tables.add(new NamedTable(sheet.getSheetName(), table));
            }
        }
        return tables;
    }

    private CsvData parseSheet(Sheet sheet, DataFormatter formatter) {
        int headerRowIdx = findHeaderRow(sheet, formatter);
        if (headerRowIdx < 0) return null;

        Row headerRow = sheet.getRow(headerRowIdx);
        Map<Integer, String> headerByCol = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String h = formatter.formatCellValue(cell).trim();
            if (!h.isEmpty()) headerByCol.put(cell.getColumnIndex(), h);
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> record = new LinkedHashMap<>();
            boolean allBlank = true;
            for (Map.Entry<Integer, String> e : headerByCol.entrySet()) {
                Cell cell = row.getCell(e.getKey());
                String v = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (!v.isEmpty()) allBlank = false;
                record.put(e.getValue(), v);
            }
            if (!allBlank) rows.add(record);
        }
        return new CsvData(List.copyOf(headerByCol.values()), rows);
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int lastScanRow = Math.min(sheet.getLastRowNum(), MAX_HEADER_SCAN_ROWS);
        for (int r = sheet.getFirstRowNum(); r <= lastScanRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String norm = normalize(formatter.formatCellValue(cell));
                if (MOVIE_NAME_MARKERS.contains(norm)) return r;
            }
        }
        return -1;
    }

    private String normalize(String header) {
        return header.toLowerCase().trim().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
