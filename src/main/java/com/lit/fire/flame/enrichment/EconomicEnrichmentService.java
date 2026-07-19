package com.lit.fire.flame.enrichment;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * One-shot backfill that fills "gdp_usd_billions" and "inflation_rate_pct" (via the
 * World Bank API, see WorldBankClient) for every Indian-language movie in
 * movies_data_collection released after 2000 that doesn't have them yet.
 *
 * Unlike the CSV-import enrichment path (EnrichmentService, wired into CsvDataFiller),
 * this reaches rows already sitting in the table — e.g. ones inserted by the box-office/
 * actor crawlers, which never go through CsvDataFiller's enrichment step.
 *
 * Since GDP/inflation are keyed only by country + year, and every candidate row resolves
 * to the same country (India), this fetches at most one World Bank value pair per distinct
 * release year rather than per movie — a handful of API calls instead of tens of thousands.
 */
public class EconomicEnrichmentService {

    private static final String PREFIX  = "[ECON] ";
    private static final String COUNTRY = "IN";

    /** Runs one backfill cycle synchronously, then returns. Intended for --econ-scan-once. */
    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String tableName  = config.getProperty("table.name", "movies_data_collection");

        log("=== Starting GDP/inflation backfill for Indian-language movies released after 2000 ===");

        List<Integer> years;
        try (EconomicDatabaseService db = new EconomicDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
            db.ensureColumnsExist();
            years = db.getYearsNeedingEnrichment();
        }

        if (years.isEmpty()) {
            log("Nothing to do — every eligible movie already has GDP and inflation filled in.");
            return;
        }
        log(String.format("Found %,d release year(s) with movies still missing GDP/inflation: %s",
            years.size(), years));

        WorldBankClient worldBank = new WorldBankClient();
        int yearsDone = 0, yearsNoData = 0, totalRowsUpdated = 0;

        for (int idx = 0; idx < years.size(); idx++) {
            int year = years.get(idx);
            String progress = String.format("[%d/%d]", idx + 1, years.size());

            Double gdp, inflation;
            try {
                gdp       = worldBank.fetchGdpBillions(COUNTRY, year);
                inflation = worldBank.fetchInflationRate(COUNTRY, year);
            } catch (Exception e) {
                logErr(String.format("%s year %d — World Bank lookup failed: %s", progress, year, e.getMessage()));
                continue;
            }

            if (gdp == null && inflation == null) {
                yearsNoData++;
                log(String.format("%s year %d — World Bank has no data for India in this year. Skipping.",
                    progress, year));
                continue;
            }

            try (EconomicDatabaseService db = new EconomicDatabaseService(dbUrl, dbUser, dbPassword, tableName)) {
                int updated = db.updateYearIfMissing(year, gdp, inflation);
                totalRowsUpdated += updated;
                yearsDone++;
                log(String.format(
                    "%s year %d — GDP: %s | inflation: %s%% | rows updated: %,d",
                    progress, year,
                    gdp != null ? gdp + "B USD" : "n/a",
                    inflation != null ? inflation : "n/a",
                    updated));
            } catch (Exception e) {
                logErr(String.format("%s year %d — DB update failed: %s", progress, year, e.getMessage()));
            }
        }

        log(String.format(
            "=== Backfill complete — years processed: %,d | years with no World Bank data: %,d | total rows updated: %,d ===",
            yearsDone, yearsNoData, totalRowsUpdated));
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
    }

    private void logErr(String msg) {
        System.err.println(PREFIX + msg);
        System.err.flush();
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
