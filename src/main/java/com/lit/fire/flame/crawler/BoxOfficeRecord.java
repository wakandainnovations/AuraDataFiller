package com.lit.fire.flame.crawler;

/**
 * Holds box office and metadata scraped from any source for a single movie.
 *
 * INR-Crore sources (sacnilk, koimoi): worldwideCr and budgetCr are populated;
 * revenueUsd and budgetUsd are null (caller converts via ExchangeRateService).
 *
 * USD-native sources (boxofficemojo): revenueUsd and budgetUsd are populated;
 * worldwideCr and budgetCr are null (no conversion needed).
 *
 * Metadata fields (genre, language, runtimeMinutes, rating, status) are
 * populated only when the source provides them; null otherwise.
 */
public record BoxOfficeRecord(
    String  movieName,       // Display name
    String  year,            // 4-digit release year
    String  slug,            // Source-specific identifier (sacnilk slug, BOM path, Koimoi URL)
    Double  worldwideCr,     // Worldwide collection in INR Crore; null for USD-native sources
    Double  budgetCr,        // Production budget in INR Crore; null for USD-native sources
    Long    revenueUsd,      // Pre-converted worldwide gross in whole USD; null for INR-Crore sources
    Long    budgetUsd,       // Pre-converted budget in whole USD; null for INR-Crore sources
    String  genre,           // Comma-separated genre list, e.g. "Action, Drama"; null if unknown
    String  language,        // Comma-separated language list, e.g. "Hindi, Telugu"; null if unknown
    Integer runtimeMinutes,  // Runtime in minutes (2h 30m → 150); null if not available
    Double  rating,          // User/site rating out of 10; null if not available
    String  status           // "Released" or "Upcoming"; null if indeterminate
) {
    /** Convenience constructor for INR-Crore sources with metadata (sacnilk). */
    public BoxOfficeRecord(String movieName, String year, String slug,
                           Double worldwideCr, Double budgetCr,
                           String genre, String language,
                           Integer runtimeMinutes, Double rating, String status) {
        this(movieName, year, slug, worldwideCr, budgetCr, null, null,
             genre, language, runtimeMinutes, rating, status);
    }

    /** Convenience constructor for INR-Crore sources without metadata (koimoi). */
    public BoxOfficeRecord(String movieName, String year, String slug,
                           Double worldwideCr, Double budgetCr) {
        this(movieName, year, slug, worldwideCr, budgetCr, null, null,
             null, null, null, null, null);
    }
}
