package com.lit.fire.flame.crawler;

/**
 * Holds box office data scraped from any source for a single movie.
 *
 * INR-Crore sources (sacnilk, koimoi): worldwideCr and budgetCr are populated;
 * revenueUsd and budgetUsd are null (caller converts via ExchangeRateService).
 *
 * USD-native sources (boxofficemojo): revenueUsd and budgetUsd are populated;
 * worldwideCr and budgetCr are null (no conversion needed).
 */
public record BoxOfficeRecord(
    String movieName,    // Display name
    String year,         // 4-digit release year
    String slug,         // Source-specific identifier (sacnilk slug, BOM path, Koimoi URL)
    Double worldwideCr,  // Worldwide collection in INR Crore; null for USD-native sources
    Double budgetCr,     // Production budget in INR Crore; null for USD-native sources
    Long   revenueUsd,   // Pre-converted worldwide gross in whole USD; null for INR-Crore sources
    Long   budgetUsd     // Pre-converted budget in whole USD; null for INR-Crore sources
) {
    /** Convenience constructor for INR-Crore sources (sacnilk, koimoi). */
    public BoxOfficeRecord(String movieName, String year, String slug,
                           Double worldwideCr, Double budgetCr) {
        this(movieName, year, slug, worldwideCr, budgetCr, null, null);
    }
}
