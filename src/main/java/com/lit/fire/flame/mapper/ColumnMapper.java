package com.lit.fire.flame.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ColumnMapper {

    public static final String MOVIE_NAME_COL = "movie_name";
    public static final String YEAR_COL = "year";

    private static final Set<String> MOVIE_NAME_ALIASES = Set.of("movie name", "movie", "film");
    private static final Set<String> NUMERIC_PG_TYPES = Set.of(
        "numeric", "integer", "bigint", "smallint", "real",
        "double precision", "decimal", "float"
    );

    /**
     * Maps a CSV header to a PostgreSQL column name.
     * Normalises "Movie Name", "Movie", "Film" → movie_name.
     * All others: lowercase + replace non-alphanumeric runs with '_', strip edge underscores.
     */
    public String toDbColumnName(String csvHeader) {
        String lower = csvHeader.toLowerCase().trim();
        if (MOVIE_NAME_ALIASES.contains(lower)) return MOVIE_NAME_COL;
        if ("year".equals(lower))               return YEAR_COL;
        return lower.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    public boolean isPkColumn(String dbColumnName) {
        return MOVIE_NAME_COL.equals(dbColumnName) || YEAR_COL.equals(dbColumnName);
    }

    /**
     * Infers whether a column should be NUMERIC or TEXT by examining its sample values.
     * A column is NUMERIC only when every non-null, non-dash value parses as a number
     * (after stripping commas and a trailing " min").
     */
    public ColumnType inferType(List<String> values) {
        long meaningful = values.stream()
            .filter(v -> v != null && !v.isBlank() && !"-".equals(v.trim()))
            .count();
        if (meaningful == 0) return ColumnType.TEXT;

        long numeric = values.stream()
            .filter(v -> v != null && !v.isBlank() && !"-".equals(v.trim()))
            .filter(v -> parseAsNumber(v).isPresent())
            .count();
        return numeric == meaningful ? ColumnType.NUMERIC : ColumnType.TEXT;
    }

    /**
     * Sanitises a raw CSV value for insertion into the DB.
     * Returns null for blank / "-" values.
     * For numeric columns, strips commas and trailing " min" before returning.
     */
    public String sanitizeValue(String value, String pgDataType) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) return null;
        if (isNumericType(pgDataType)) {
            return sanitizeNumericString(trimmed).orElse(null);
        }
        return trimmed;
    }

    public boolean isNumericType(String pgDataType) {
        if (pgDataType == null) return false;
        String lower = pgDataType.toLowerCase();
        return NUMERIC_PG_TYPES.stream().anyMatch(lower::contains);
    }

    // ---- private helpers ----

    private Optional<String> sanitizeNumericString(String value) {
        String cleaned = value
            .replace(",", "")
            .replaceAll("(?i)\\s*min\\s*$", "")
            .trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) return Optional.empty();
        try {
            new BigDecimal(cleaned);
            return Optional.of(cleaned);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> parseAsNumber(String value) {
        String cleaned = value.trim()
            .replace(",", "")
            .replaceAll("(?i)\\s*min\\s*$", "")
            .trim();
        if (cleaned.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(cleaned));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public enum ColumnType {
        TEXT, NUMERIC
    }
}
