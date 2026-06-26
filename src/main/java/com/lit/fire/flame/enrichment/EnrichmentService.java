package com.lit.fire.flame.enrichment;

/**
 * Orchestrates external enrichment lookups for a single movie row.
 * Uses only free APIs:
 *   - World Bank (no auth, no cost) for GDP and inflation
 *   - Anthropic Claude Haiku (pay-per-token, requires key) for event detection
 * Each sub-client handles its own in-memory caching, so repeated calls for the
 * same (country, year) or (country, date) pair incur only one real API call.
 */
public class EnrichmentService {

    private final CountryResolver countryResolver = new CountryResolver();
    private final WorldBankClient worldBankClient;
    private final ClaudeEventClient claudeEventClient;

    private final boolean worldBankEnabled;
    private final boolean claudeEnabled;

    public EnrichmentService(
            boolean worldBankEnabled,
            boolean claudeEnabled,
            String claudeApiKey,
            long apiDelayMs
    ) {
        this.worldBankEnabled = worldBankEnabled;
        this.claudeEnabled    = claudeEnabled && claudeApiKey != null && !claudeApiKey.isBlank();

        this.worldBankClient   = worldBankEnabled ? new WorldBankClient() : null;
        this.claudeEventClient = this.claudeEnabled ? new ClaudeEventClient(claudeApiKey, apiDelayMs) : null;

        if (worldBankEnabled) System.out.println("  [Enrichment] World Bank GDP/Inflation: enabled");
        if (this.claudeEnabled) System.out.println("  [Enrichment] Claude event detection: enabled");
        else if (claudeEnabled) System.out.println("  [Enrichment] Claude event detection: disabled (no api key)");
    }

    /**
     * Enriches a single movie row.
     *
     * @param releaseDate YYYY-MM-DD (may be null)
     * @param langOrName  2-char ISO language code ("hi") OR expanded name ("Hindi") — used for
     *                    country inference when no explicit country is provided
     * @param countryName explicit country name from the CSV, if any (may be null)
     */
    public EnrichmentResult enrich(String releaseDate, String langOrName, String countryName) {
        // Resolve country: explicit country column takes precedence over language inference
        String[] countryInfo = null;
        if (countryName != null && !countryName.isBlank()) {
            countryInfo = countryResolver.resolveFromCountryName(countryName);
        }
        if (countryInfo == null && langOrName != null && !langOrName.isBlank()) {
            countryInfo = countryResolver.resolve(langOrName);
        }

        // Extract release year
        int year = -1;
        if (releaseDate != null && releaseDate.length() >= 4) {
            try { year = Integer.parseInt(releaseDate.substring(0, 4)); } catch (NumberFormatException ignored) {}
        }

        // GDP & inflation (World Bank — free, no auth)
        Double gdp = null, inflation = null;
        if (worldBankEnabled && worldBankClient != null && countryInfo != null && year > 0) {
            gdp       = worldBankClient.fetchGdpBillions(countryInfo[0], year);
            inflation = worldBankClient.fetchInflationRate(countryInfo[0], year);
        }

        // Events (Claude Haiku) — only meaningful for full YYYY-MM-DD dates, not year-only values
        String eventType = null, eventName = null, eventDetail = null;
        boolean isFullDate = releaseDate != null && releaseDate.matches("\\d{4}-\\d{2}-\\d{2}");
        if (claudeEnabled && claudeEventClient != null && isFullDate && countryInfo != null) {
            String[] events = claudeEventClient.fetchEventInfo(releaseDate, countryInfo[1]);
            eventType   = events[0];
            eventName   = events[1];
            eventDetail = events[2];
        }

        return new EnrichmentResult(gdp, inflation, eventType, eventName, eventDetail);
    }
}
