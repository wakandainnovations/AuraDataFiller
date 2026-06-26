package com.lit.fire.flame.enrichment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a 2-char ISO language code or expanded language name to a
 * [ISO-3166-1-alpha-2 country code, human-readable country name] pair.
 */
public class CountryResolver {

    // Maps both language codes (e.g. "hi") and full names (e.g. "hindi") to [isoCode, countryName]
    private static final Map<String, String[]> RESOLVER = new HashMap<>();

    static {
        // Indian languages → India
        for (String code : List.of("hi", "kn", "te", "ta", "ml", "mr", "bn", "pa", "gu", "or", "bho", "as", "mai", "kok", "sa")) {
            RESOLVER.put(code, new String[]{"IN", "India"});
        }
        for (String name : List.of("Hindi", "Kannada", "Telugu", "Tamil", "Malayalam", "Marathi",
                "Bengali", "Punjabi", "Gujarati", "Odia", "Bhojpuri", "Assamese",
                "Maithili", "Konkani", "Sanskrit")) {
            RESOLVER.put(name.toLowerCase(), new String[]{"IN", "India"});
        }

        reg("ur", "Urdu",       "PK", "Pakistan");
        reg("en", "English",    "US", "United States");
        reg("fr", "French",     "FR", "France");
        reg("de", "German",     "DE", "Germany");
        reg("ja", "Japanese",   "JP", "Japan");
        reg("ko", "Korean",     "KR", "South Korea");
        reg("zh", "Chinese",    "CN", "China");
        reg("es", "Spanish",    "ES", "Spain");
        reg("pt", "Portuguese", "BR", "Brazil");
        reg("it", "Italian",    "IT", "Italy");
        reg("ru", "Russian",    "RU", "Russia");
        reg("ar", "Arabic",     "SA", "Saudi Arabia");
        reg("si", "Sinhala",    "LK", "Sri Lanka");
        reg("ne", "Nepali",     "NP", "Nepal");
        reg("th", "Thai",       "TH", "Thailand");
        reg("tr", "Turkish",    "TR", "Turkey");
        reg("id", "Indonesian", "ID", "Indonesia");
        reg("vi", "Vietnamese", "VN", "Vietnam");
        reg("nl", "Dutch",      "NL", "Netherlands");
        reg("pl", "Polish",     "PL", "Poland");
        reg("sv", "Swedish",    "SE", "Sweden");
        reg("da", "Danish",     "DK", "Denmark");
        reg("fi", "Finnish",    "FI", "Finland");
        reg("nb", "Norwegian",  "NO", "Norway");
        reg("cs", "Czech",      "CZ", "Czech Republic");
        reg("hu", "Hungarian",  "HU", "Hungary");
        reg("ro", "Romanian",   "RO", "Romania");
        reg("he", "Hebrew",     "IL", "Israel");
    }

    private static void reg(String code, String name, String isoCode, String countryName) {
        String[] val = new String[]{isoCode, countryName};
        RESOLVER.put(code, val);
        RESOLVER.put(name.toLowerCase(), val);
    }

    /**
     * Resolves from a 2-char language code OR a full language name (case-insensitive).
     * Returns [ISO country code, country name], or null if unrecognised.
     */
    public String[] resolve(String langCodeOrName) {
        if (langCodeOrName == null) return null;
        return RESOLVER.get(langCodeOrName.trim().toLowerCase());
    }

    /**
     * Resolves from a bare country name string (e.g. "India", "United States").
     * Does a case-insensitive substring match as fallback.
     * Returns [ISO country code, country name], or null if unrecognised.
     */
    public String[] resolveFromCountryName(String countryName) {
        if (countryName == null) return null;
        String lower = countryName.trim().toLowerCase();
        // First pass: exact lower-case match against known country names in the resolver
        for (Map.Entry<String, String[]> e : RESOLVER.entrySet()) {
            if (e.getValue()[1].toLowerCase().equals(lower)) {
                return e.getValue();
            }
        }
        // Second pass: substring containment
        for (Map.Entry<String, String[]> e : RESOLVER.entrySet()) {
            String knownLower = e.getValue()[1].toLowerCase();
            if (knownLower.contains(lower) || lower.contains(knownLower)) {
                return e.getValue();
            }
        }
        return null;
    }
}
