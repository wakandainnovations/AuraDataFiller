package com.lit.fire.flame.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;

public class ColumnMapper {

    public static final String MOVIE_NAME_COL   = "movie_name";
    public static final String RELEASE_DATE_COL = "release_date";
    public static final String RELEASE_DAY_COL  = "release_day";

    // Enrichment columns added by EnrichmentService
    public static final String GDP_COL          = "gdp_usd_billions";
    public static final String INFLATION_COL    = "inflation_rate_pct";
    public static final String EVENT_TYPE_COL   = "release_event_type";
    public static final String EVENT_NAME_COL   = "release_event_name";
    public static final String EVENT_DETAIL_COL = "release_event_detail";

    // Pre-declared types for enrichment columns (prevents TEXT inference when all values are null)
    private static final Map<String, ColumnType> KNOWN_COLUMN_TYPES = new HashMap<>();
    static {
        KNOWN_COLUMN_TYPES.put(GDP_COL,       ColumnType.NUMERIC);
        KNOWN_COLUMN_TYPES.put(INFLATION_COL, ColumnType.NUMERIC);
    }

    private static final Set<String> MOVIE_NAME_ALIASES = Set.of(
        "movie name", "movie", "film", "title"
    );

    private static final Set<String> SKIP_CSV_HEADERS = Set.of("overview");

    private static final Set<String> NUMERIC_PG_TYPES = Set.of(
        "numeric", "integer", "bigint", "smallint", "real",
        "double precision", "decimal", "float"
    );

    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
        Map.entry("hi", "Hindi"),
        Map.entry("kn", "Kannada"),
        Map.entry("ml", "Malayalam"),
        Map.entry("te", "Telugu"),
        Map.entry("ta", "Tamil"),
        Map.entry("en", "English"),
        Map.entry("mr", "Marathi"),
        Map.entry("bn", "Bengali"),
        Map.entry("pa", "Punjabi"),
        Map.entry("gu", "Gujarati"),
        Map.entry("or", "Odia"),
        Map.entry("ur", "Urdu"),
        Map.entry("sa", "Sanskrit"),
        Map.entry("si", "Sinhala"),
        Map.entry("ne", "Nepali"),
        Map.entry("fr", "French"),
        Map.entry("de", "German"),
        Map.entry("es", "Spanish"),
        Map.entry("ja", "Japanese"),
        Map.entry("zh", "Chinese"),
        Map.entry("ko", "Korean"),
        Map.entry("ar", "Arabic"),
        Map.entry("pt", "Portuguese"),
        Map.entry("it", "Italian"),
        Map.entry("ru", "Russian")
    );

    /**
     * Maps a CSV header to a PostgreSQL column name.
     * Handles well-known aliases and renames; everything else is lowercased
     * with non-alphanumeric runs replaced by '_'.
     */
    public String toDbColumnName(String csvHeader) {
        String lower = csvHeader.toLowerCase().trim();
        if (MOVIE_NAME_ALIASES.contains(lower)) return MOVIE_NAME_COL;
        if ("year".equals(lower) || "release_date".equals(lower)) return RELEASE_DATE_COL;
        if ("vote_average".equals(lower))    return "rating_10";
        if ("vote_count".equals(lower))      return "votes";
        if ("original_language".equals(lower)) return "language";
        return lower.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Returns a pre-declared ColumnType for known enrichment columns, bypassing type inference. */
    public Optional<ColumnType> getKnownColumnType(String dbColumnName) {
        return Optional.ofNullable(KNOWN_COLUMN_TYPES.get(dbColumnName));
    }

    public boolean isPkColumn(String dbColumnName) {
        return MOVIE_NAME_COL.equals(dbColumnName) || RELEASE_DATE_COL.equals(dbColumnName);
    }

    public boolean shouldSkipCsvHeader(String csvHeader) {
        return SKIP_CSV_HEADERS.contains(csvHeader.toLowerCase().trim());
    }

    /** Expands a 2-character language code to its full name; returns the original value if unknown. */
    public String expandLanguageCode(String code) {
        if (code == null) return null;
        String lower = code.trim().toLowerCase();
        return LANGUAGE_CODES.getOrDefault(lower, code.trim());
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
