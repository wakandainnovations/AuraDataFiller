package com.lit.fire.flame.languageid;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Normalizes AuraLLM's free-text reply to a language-identification prompt into a clean
 * lowercase language value, or null when the reply isn't a usable answer. AuraLLM's active
 * provider doesn't always obey "one word only" (see ReleaseEventParser/MarketingTacticsParser
 * for the same tolerance elsewhere in this app), so this strips quoting/punctuation and takes
 * only the first line before validating.
 *
 * A genuinely multilingual production (shot simultaneously in more than one language — the
 * prompt asks AuraLLM to distinguish this from a film merely dubbed into other languages after
 * the fact) comes back as a comma/"and"-separated list; this normalizes it to the same
 * comma-space-separated, alphabetically-sorted, deduped format already used for existing
 * multi-language rows in movies_data_collection (e.g. "hindi, tamil, telugu").
 */
final class LanguageIdParser {

    private static final Pattern VALID_LANGUAGE = Pattern.compile("^[a-z][a-z '-]{1,29}$");
    private static final Pattern AND_SEPARATOR = Pattern.compile("\\s+and\\s+|\\s*&\\s*");
    private static final int MAX_LANGUAGES = 6;

    private LanguageIdParser() {}

    /** @return a clean lowercase language value (single, or "a, b, c" for multilingual), or null if unresolved/unparseable. */
    static String parse(String reply) {
        if (reply == null) return null;

        String firstLine = reply.strip().split("\\R", 2)[0].strip();
        String cleaned = firstLine
            .replaceAll("^[\"'`]+|[\"'`.!]+$", "")
            .trim()
            .toLowerCase();

        if (cleaned.isEmpty() || cleaned.contains("unknown") || cleaned.contains("not sure")
                || cleaned.contains("don't know") || cleaned.contains("cannot")
                || cleaned.equals("none") || cleaned.equals("n a") || cleaned.equals("na")
                || cleaned.equals("silent") || cleaned.equals("no dialogue")) {
            return null;
        }

        String normalized = AND_SEPARATOR.matcher(cleaned).replaceAll(", ");
        String[] parts = normalized.split(",");
        if (parts.length > MAX_LANGUAGES) return null;

        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty() || !VALID_LANGUAGE.matcher(token).matches()) return null;
            tokens.add(token);
        }

        TreeSet<String> unique = new TreeSet<>(tokens);
        return String.join(", ", unique);
    }
}
