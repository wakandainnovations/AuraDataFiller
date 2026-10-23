package com.lit.fire.flame.marketing;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps the language values found in movies_data_collection / managed_entities (a mix of TMDB's
 * ISO 639-1 codes like "lv", full names like "tamil" and capitalised names like "Tamil") to the
 * full lowercase language name movie_marketing_tactics stores, e.g. "lv" -> "latvian".
 *
 * Follows TMDB's conventions where they differ from plain ISO 639-1: "cn" is Cantonese and
 * "xx" is "No Language". A comma-separated multi-language value ("Hindi, TA") is normalised
 * part by part ("hindi, tamil"). Anything not in the map is just lowercased and trimmed.
 */
public final class LanguageNames {

    private static final Map<String, String> CODE_TO_NAME = Map.ofEntries(
        Map.entry("ab", "abkhazian"),       Map.entry("af", "afrikaans"),      Map.entry("ak", "akan"),
        Map.entry("am", "amharic"),         Map.entry("ar", "arabic"),         Map.entry("as", "assamese"),
        Map.entry("ay", "aymara"),          Map.entry("az", "azerbaijani"),    Map.entry("ba", "bashkir"),
        Map.entry("be", "belarusian"),      Map.entry("bg", "bulgarian"),      Map.entry("bi", "bislama"),
        Map.entry("bm", "bambara"),         Map.entry("bn", "bengali"),        Map.entry("bo", "tibetan"),
        Map.entry("br", "breton"),          Map.entry("bs", "bosnian"),        Map.entry("ca", "catalan"),
        Map.entry("ce", "chechen"),         Map.entry("cn", "cantonese"),      Map.entry("cr", "cree"),
        Map.entry("cs", "czech"),           Map.entry("cy", "welsh"),          Map.entry("da", "danish"),
        Map.entry("de", "german"),          Map.entry("dv", "divehi"),         Map.entry("dz", "dzongkha"),
        Map.entry("el", "greek"),           Map.entry("en", "english"),        Map.entry("eo", "esperanto"),
        Map.entry("es", "spanish"),         Map.entry("et", "estonian"),       Map.entry("eu", "basque"),
        Map.entry("fa", "persian"),         Map.entry("ff", "fula"),           Map.entry("fi", "finnish"),
        Map.entry("fj", "fijian"),          Map.entry("fo", "faroese"),        Map.entry("fr", "french"),
        Map.entry("fy", "frisian"),         Map.entry("ga", "irish"),          Map.entry("gl", "galician"),
        Map.entry("gn", "guarani"),         Map.entry("gu", "gujarati"),       Map.entry("ha", "hausa"),
        Map.entry("he", "hebrew"),          Map.entry("hi", "hindi"),          Map.entry("hr", "croatian"),
        Map.entry("ht", "haitian creole"),  Map.entry("hu", "hungarian"),      Map.entry("hy", "armenian"),
        Map.entry("hz", "herero"),          Map.entry("ia", "interlingua"),    Map.entry("id", "indonesian"),
        Map.entry("ie", "interlingue"),     Map.entry("ig", "igbo"),           Map.entry("is", "icelandic"),
        Map.entry("it", "italian"),         Map.entry("iu", "inuktitut"),      Map.entry("ja", "japanese"),
        Map.entry("jv", "javanese"),        Map.entry("ka", "georgian"),       Map.entry("kk", "kazakh"),
        Map.entry("kl", "greenlandic"),     Map.entry("km", "khmer"),          Map.entry("kn", "kannada"),
        Map.entry("ko", "korean"),          Map.entry("ks", "kashmiri"),       Map.entry("ku", "kurdish"),
        Map.entry("ky", "kyrgyz"),          Map.entry("la", "latin"),          Map.entry("lb", "luxembourgish"),
        Map.entry("lg", "ganda"),           Map.entry("li", "limburgish"),     Map.entry("lo", "lao"),
        Map.entry("lt", "lithuanian"),      Map.entry("lv", "latvian"),        Map.entry("mg", "malagasy"),
        Map.entry("mh", "marshallese"),     Map.entry("mi", "maori"),          Map.entry("mk", "macedonian"),
        Map.entry("ml", "malayalam"),       Map.entry("mn", "mongolian"),      Map.entry("mo", "moldavian"),
        Map.entry("mr", "marathi"),         Map.entry("ms", "malay"),          Map.entry("mt", "maltese"),
        Map.entry("my", "burmese"),         Map.entry("nb", "norwegian bokmål"), Map.entry("ne", "nepali"),
        Map.entry("nl", "dutch"),           Map.entry("nn", "norwegian nynorsk"), Map.entry("no", "norwegian"),
        Map.entry("nv", "navajo"),          Map.entry("ny", "chichewa"),       Map.entry("om", "oromo"),
        Map.entry("or", "odia"),            Map.entry("os", "ossetian"),       Map.entry("pa", "punjabi"),
        Map.entry("pl", "polish"),          Map.entry("ps", "pashto"),         Map.entry("pt", "portuguese"),
        Map.entry("qu", "quechua"),         Map.entry("rm", "romansh"),        Map.entry("rn", "kirundi"),
        Map.entry("ro", "romanian"),        Map.entry("ru", "russian"),        Map.entry("rw", "kinyarwanda"),
        Map.entry("sa", "sanskrit"),        Map.entry("sd", "sindhi"),         Map.entry("se", "northern sami"),
        Map.entry("sh", "serbo-croatian"),  Map.entry("si", "sinhala"),        Map.entry("sk", "slovak"),
        Map.entry("sl", "slovenian"),       Map.entry("sm", "samoan"),         Map.entry("sn", "shona"),
        Map.entry("so", "somali"),          Map.entry("sq", "albanian"),       Map.entry("sr", "serbian"),
        Map.entry("ss", "swati"),           Map.entry("st", "sesotho"),        Map.entry("su", "sundanese"),
        Map.entry("sv", "swedish"),         Map.entry("sw", "swahili"),        Map.entry("ta", "tamil"),
        Map.entry("te", "telugu"),          Map.entry("tg", "tajik"),          Map.entry("th", "thai"),
        Map.entry("ti", "tigrinya"),        Map.entry("tk", "turkmen"),        Map.entry("tl", "tagalog"),
        Map.entry("tr", "turkish"),         Map.entry("tt", "tatar"),          Map.entry("tw", "twi"),
        Map.entry("ug", "uyghur"),          Map.entry("uk", "ukrainian"),      Map.entry("ur", "urdu"),
        Map.entry("uz", "uzbek"),           Map.entry("vi", "vietnamese"),     Map.entry("wo", "wolof"),
        Map.entry("xh", "xhosa"),           Map.entry("xx", "no language"),    Map.entry("yi", "yiddish"),
        Map.entry("yo", "yoruba"),          Map.entry("zh", "chinese"),        Map.entry("zu", "zulu"),
        // Non-code spellings that mean a name the table already uses.
        Map.entry("mandarin", "chinese"),   Map.entry("mandarin chinese", "chinese"),
        Map.entry("oriya", "odia"),         Map.entry("rajastani", "rajasthani")
    );

    private LanguageNames() {}

    /** Full lowercase name for {@code value}; null/blank becomes "unknown". */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        if (value.indexOf(',') >= 0) {
            String joined = java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(LanguageNames::normalizeOne)
                .collect(Collectors.joining(", "));
            return joined.isEmpty() ? "unknown" : joined;
        }
        return normalizeOne(value);
    }

    private static String normalizeOne(String value) {
        String key = value.trim().toLowerCase();
        return CODE_TO_NAME.getOrDefault(key, key);
    }

    /**
     * SQL expression that normalises the language column {@code expr} exactly like
     * {@link #normalize}, comma lists included, so queries that join on the stored language
     * (e.g. the scan-status join in getCandidates) compare like with like.
     */
    public static String sqlNormalize(String expr) {
        // The map is a jsonb literal (parsed once per statement) rather than a long CASE: called
        // per row of movies_data_collection (~390K rows), a CASE + unnest() costs ~20us a call,
        // the jsonb lookup ~2.5us. Single values — nearly all rows — skip the list split.
        String map = CODE_TO_NAME.entrySet().stream()
            .map(e -> '"' + e.getKey() + "\":\"" + e.getValue() + '"')
            .collect(Collectors.joining(",", "'{", "}'::jsonb"));
        String one = "COALESCE(" + map + " ->> LOWER(TRIM(%1$s)), LOWER(TRIM(%1$s)))";
        return "CASE WHEN strpos(" + expr + ", ',') = 0 " +
               "THEN COALESCE(NULLIF(" + String.format(one, expr) + ", ''), 'unknown') " +
               "ELSE COALESCE((SELECT string_agg(" + String.format(one, "u.p") + ", ', ' ORDER BY u.ord) " +
               "FROM unnest(string_to_array(" + expr + ", ',')) WITH ORDINALITY AS u(p, ord) " +
               "WHERE TRIM(u.p) <> ''), 'unknown') END";
    }
}
