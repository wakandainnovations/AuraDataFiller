package com.lit.fire.flame.crawler;

/**
 * Holds box office data scraped from sacnilk.com for a single movie.
 * All monetary values are in Indian Rupees Crore (INR Cr).
 */
public record BoxOfficeRecord(
    String movieName,    // Display name extracted from the sacnilk slug
    String year,         // 4-digit release year extracted from slug
    String slug,         // sacnilk URL slug, e.g. "KGF_Chapter_2_2022"
    Double worldwideCr,  // Total Worldwide Collection in INR Crore; null if not found on page
    Double budgetCr      // Production Budget in INR Crore; null if N/A or not found
) {}
