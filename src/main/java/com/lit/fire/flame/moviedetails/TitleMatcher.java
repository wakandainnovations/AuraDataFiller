package com.lit.fire.flame.moviedetails;

/**
 * Decides whether two film titles from different sources name the same film: equal after
 * normalization, a near-identical spelling with exactly the same digits (so "Dhoom 2" never
 * matches "Dhoom 3"), or one side being the other's title without its subtitle ("Lagaan" vs
 * "Lagaan: Once Upon a Time in India"). Two different subtitles of one main title ("Baahubali:
 * The Beginning" vs "Baahubali: The Conclusion") never match. Callers still need their own
 * year/language checks — a title match alone doesn't identify a film.
 */
public final class TitleMatcher {

    /** Near-identical title spelling accepted as the same movie (digits must still match exactly). */
    private static final double FUZZY_TITLE_SIMILARITY = 0.90;

    private TitleMatcher() {}

    public static boolean matches(String a, String b) {
        String[] fa = forms(a), fb = forms(b);
        return same(fa[0], fb[0]) || same(fa[0], fb[1]) || same(fa[1], fb[0]);
    }

    /** {normalized full title, normalized main title} — the main title drops a ":"/" - "/"(...)" subtitle. */
    private static String[] forms(String title) {
        if (title == null) return new String[] {"", ""};
        String main = title.split(":| - |\\(", 2)[0];
        return new String[] {TmdbClient.normalize(title), TmdbClient.normalize(main)};
    }

    private static boolean same(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        return a.replaceAll("\\D", "").equals(b.replaceAll("\\D", ""))
            && TmdbClient.similarity(a, b) >= FUZZY_TITLE_SIMILARITY;
    }
}
