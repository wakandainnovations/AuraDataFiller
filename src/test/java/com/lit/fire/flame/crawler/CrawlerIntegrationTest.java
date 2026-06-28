package com.lit.fire.flame.crawler;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * Integration test: verifies that the sacnilk crawler correctly fetches,
 * converts to USD, and persists revenue/budget for 'Hoshiar Singh' and 'Dhurandhar'.
 *
 * Makes real HTTP calls to sacnilk.com and api.frankfurter.app.
 * Requires the local PostgreSQL database to be running with these movies present.
 */
public class CrawlerIntegrationTest {

    private static final String TABLE = "movies_data_collection";

    // Movies under test: [movie_name, release_date, expected_year]
    private static final String[][] TARGET_MOVIES = {
        {"Hoshiar Singh", "2025-02-07", "2025"},
        {"Dhurandhar",    "2025-12-05", "2025"},
    };

    @Test
    public void testCrawlerUpdatesRevenueAndBudgetInUsd() throws Exception {
        Properties secrets = loadProperties("secrets.properties");
        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");

        SacnilkHtmlParser  parser       = new SacnilkHtmlParser();
        ExchangeRateService exchangeRate = new ExchangeRateService();

        System.out.println("\n=== CrawlerIntegrationTest: Reset → Crawl → Verify ===\n");

        // Step 1: Reset revenue/budget for both target movies so we can detect fresh writes
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            conn.setAutoCommit(false);
            // Ensure columns exist via service (safe to call multiple times)
            try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, TABLE)) {
                db.ensureColumnsExist();
            }
            for (String[] m : TARGET_MOVIES) {
                resetBoxOffice(conn, m[0], m[2]);
                System.out.printf("RESET  %-30s (%s) → revenue=NULL, budget=NULL%n", m[0], m[2]);
            }
            conn.commit();
        }

        // Step 2: Fetch the sacnilk sitemap and build a year-keyed slug index
        System.out.println("\nFetching sacnilk sitemap...");
        List<String> allSlugs = parser.fetchMovieSlugsFromSitemap();
        System.out.printf("Found %d slugs in sitemap.%n%n", allSlugs.size());

        Map<String, List<String>> slugsByYear = new HashMap<>();
        for (String slug : allSlugs) {
            String yr = parser.extractYearFromSlug(slug);
            if (yr != null) slugsByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(slug);
        }

        // Step 3: For each target movie, find the best-matching slug, fetch & convert
        Map<String, Long[]> results = new LinkedHashMap<>(); // movie_name → [revenueUsd, budgetUsd]

        for (String[] target : TARGET_MOVIES) {
            String movieName  = target[0];
            String releaseDate = target[1];
            String year        = target[2];

            System.out.printf("--- %s (%s) ---%n", movieName, year);

            String bestSlug  = findBestSlug(slugsByYear.getOrDefault(year, List.of()),
                                            movieName, parser, 0.70);
            if (bestSlug == null) {
                System.out.printf("  WARNING: no sacnilk slug matched '%s' — skipping.%n%n", movieName);
                results.put(movieName, new Long[]{null, null});
                continue;
            }
            System.out.printf("  Matched slug: %s%n", bestSlug);

            BoxOfficeRecord rec = parser.parseMovieDetailPage(bestSlug);
            System.out.printf("  Raw    WW: %s Cr  |  Budget: %s Cr%n",
                rec.worldwideCr() != null ? rec.worldwideCr() : "N/A",
                rec.budgetCr()    != null ? rec.budgetCr()    : "N/A");

            double rate = exchangeRate.getInrToUsdRate(releaseDate);
            System.out.printf("  INR→USD rate (%s): %.6f%n", releaseDate, rate);

            Long revenueUsd = rec.worldwideCr() != null
                ? exchangeRate.inrCroreToUsd(rec.worldwideCr(), rate) : null;
            Long budgetUsd  = rec.budgetCr()    != null
                ? exchangeRate.inrCroreToUsd(rec.budgetCr(),    rate) : null;

            System.out.printf("  USD    WW: %s  |  Budget: %s%n",
                revenueUsd != null ? String.format("$%,d", revenueUsd) : "N/A",
                budgetUsd  != null ? String.format("$%,d", budgetUsd)  : "N/A");

            results.put(movieName, new Long[]{revenueUsd, budgetUsd});

            // Step 4: Write to database
            try (CrawlerDatabaseService db =
                     new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, TABLE)) {
                int rows = db.updateBoxOffice(movieName, year, revenueUsd, budgetUsd);
                System.out.printf("  DB rows updated: %d%n%n", rows);
                assertTrue("Expected at least one row updated for " + movieName, rows > 0);
            }
        }

        // Step 5: Persist exchange rates to currency_rate_xe
        Map<String, Double> cachedRates = exchangeRate.getCachedRates();
        System.out.println("Persisting " + cachedRates.size() + " exchange rate(s) to currency_rate_xe...");
        try (CrawlerDatabaseService db = new CrawlerDatabaseService(dbUrl, dbUser, dbPassword, TABLE)) {
            db.ensureRateTableExists();
            for (Map.Entry<String, Double> e : cachedRates.entrySet()) {
                db.upsertExchangeRate(e.getKey(), "INR", "USD", e.getValue());
                System.out.printf("  Saved: %s  INR→USD = %.6f%n", e.getKey(), e.getValue());
            }
        }

        // Step 6: Verify the database contains the updated values
        System.out.println("\n=== DB Verification ===");
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            for (String[] target : TARGET_MOVIES) {
                String movieName = target[0];
                String year      = target[2];
                Long[] expected  = results.get(movieName);

                String sql = "SELECT movie_name, release_date, revenue, budget " +
                    "FROM \"" + TABLE + "\" " +
                    "WHERE movie_name = ? AND LEFT(release_date::text, 4) = ? " +
                    "LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, movieName);
                    ps.setString(2, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue("Row must exist for " + movieName, rs.next());
                        long dbRevenue = rs.getLong("revenue");
                        long dbBudget  = rs.getLong("budget");
                        System.out.printf("  %-30s  revenue=$%,d  budget=$%,d%n",
                            movieName, dbRevenue, dbBudget);

                        if (expected[0] != null) {
                            assertEquals("Revenue mismatch for " + movieName,
                                (long) expected[0], dbRevenue);
                        }
                        if (expected[1] != null) {
                            assertEquals("Budget mismatch for " + movieName,
                                (long) expected[1], dbBudget);
                        }
                    }
                }
            }

            // Verify currency_rate_xe has entries for the release dates
            System.out.println("\n=== currency_rate_xe Verification ===");
            for (String[] target : TARGET_MOVIES) {
                String date = target[1];
                String sql2 = "SELECT rate_date, from_currency, to_currency, rate " +
                    "FROM currency_rate_xe " +
                    "WHERE rate_date = ?::date AND from_currency = 'INR' AND to_currency = 'USD'";
                try (PreparedStatement ps = conn.prepareStatement(sql2)) {
                    ps.setString(1, date);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            System.out.printf("  %s  INR→USD = %.6f  (from_currency=%s, to_currency=%s)%n",
                                rs.getString("rate_date"),
                                rs.getDouble("rate"),
                                rs.getString("from_currency"),
                                rs.getString("to_currency"));
                            assertEquals("from_currency must be INR", "INR", rs.getString("from_currency").trim());
                            assertEquals("to_currency must be USD",   "USD", rs.getString("to_currency").trim());
                            assertTrue("Rate must be positive", rs.getDouble("rate") > 0);
                        } else {
                            System.out.printf("  WARNING: no rate found for %s%n", date);
                        }
                    }
                }
            }
        }

        System.out.println("\n=== All assertions passed ===\n");
    }

    // ---- helpers ----

    /** Resets revenue and budget to NULL for rows matching movieName + year. */
    private void resetBoxOffice(Connection conn,
                                 String movieName, String year) throws SQLException {
        String sql = "UPDATE \"" + TABLE + "\" " +
            "SET revenue = NULL, budget = NULL " +
            "WHERE movie_name = ? AND LEFT(release_date::text, 4) = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, movieName);
            ps.setString(2, year);
            ps.executeUpdate();
        }
    }

    /** Returns the best-matching slug for movieName from the candidate list, or null. */
    private String findBestSlug(List<String> slugs, String movieName,
                                 SacnilkHtmlParser parser, double threshold) {
        String norm = SacnilkCrawlerService.normalize(movieName);
        String bestSlug  = null;
        double bestScore = 0;
        for (String slug : slugs) {
            String slugNorm = SacnilkCrawlerService.normalize(parser.extractNameFromSlug(slug));
            double score    = SacnilkCrawlerService.similarity(norm, slugNorm);
            if (score > bestScore) {
                bestScore = score;
                bestSlug  = slug;
            }
        }
        return bestScore >= threshold ? bestSlug : null;
    }

    private Properties loadProperties(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new RuntimeException(name + " not found on classpath");
            p.load(is);
        }
        return p;
    }
}
