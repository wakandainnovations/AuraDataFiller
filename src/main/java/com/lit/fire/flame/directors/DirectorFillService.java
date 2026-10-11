package com.lit.fire.flame.directors;

import com.lit.fire.flame.moviedetails.TitleMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * One-shot fill of missing directors (--director-fill-once) for:
 * <ul>
 *   <li>india_movies_data_collection.directors — keyed by the row's IMDb id (its {@code id} column)</li>
 *   <li>actors_data_collection.director — keyed by tmdb_movie_id (rows written by the TMDB
 *       lead-cast import, see com.lit.fire.flame.actor.TmdbActorImportService)</li>
 * </ul>
 * Two sources, in order:
 * <ol>
 *   <li>Wikidata, by exact IMDb/TMDB id (see WikidataDirectorClient) — no guesswork.</li>
 *   <li>For films still missing one: Wikipedia's "List of {language} films of {year}" pages (see
 *       WikipediaFilmListClient), matching the title per TitleMatcher on the release year's page
 *       first, then — for releases within 60 days of New Year, where sources most often disagree
 *       on the year — the neighbouring year's. A director is
 *       taken only when every matching row on that page agrees on it; two different directors
 *       for one title (e.g. same-titled films) means no fill.</li>
 * </ol>
 * Only empty values are filled; a director already on file is never overwritten. Safe to re-run.
 */
public class DirectorFillService {

    private static final String PREFIX = "[DIRECTOR-FILL] ";
    private static final String INDIA_TABLE = "india_movies_data_collection";
    private static final String ACTORS_TABLE = "actors_data_collection";

    /** DB language (lowercase) -> the language name Wikipedia uses in "List of X films of YYYY". */
    private static final Map<String, String> WIKIPEDIA_LANGUAGES = Map.ofEntries(
        Map.entry("hindi", "Hindi"), Map.entry("tamil", "Tamil"), Map.entry("telugu", "Telugu"),
        Map.entry("malayalam", "Malayalam"), Map.entry("kannada", "Kannada"), Map.entry("bengali", "Bengali"),
        Map.entry("marathi", "Marathi"), Map.entry("punjabi", "Punjabi"), Map.entry("gujarati", "Gujarati"),
        Map.entry("odia", "Odia"), Map.entry("oriya", "Odia"), Map.entry("assamese", "Assamese"),
        Map.entry("bhojpuri", "Bhojpuri"), Map.entry("tulu", "Tulu"), Map.entry("konkani", "Konkani"));

    /** Release dates within this many days of New Year also check the neighbouring year's list. */
    private static final int YEAR_BOUNDARY_DAYS = 60;

    /**
     * A film still missing a director: its key in the table, every title it's stored under, its
     * release year, its full release date when known (else null), and language.
     */
    record Film(String key, Set<String> titles, int year, LocalDate releaseDate, String language) {

        /** Its release year's list first; then the neighbouring year's, only near the year boundary (or with no full date). */
        List<Integer> yearsToSearch() {
            List<Integer> years = new ArrayList<>(List.of(year));
            if (releaseDate == null || releaseDate.getDayOfYear() <= YEAR_BOUNDARY_DAYS) years.add(year - 1);
            if (releaseDate == null || releaseDate.lengthOfYear() - releaseDate.getDayOfYear() < YEAR_BOUNDARY_DAYS) years.add(year + 1);
            return years;
        }
    }

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties");
        WikidataDirectorClient wikidata = new WikidataDirectorClient();
        WikipediaFilmListClient wikipedia = new WikipediaFilmListClient();

        try (Connection conn = DriverManager.getConnection(secrets.getProperty("db.url"),
                secrets.getProperty("db.user"), secrets.getProperty("db.password", ""))) {

            // ---- india_movies_data_collection, keyed by IMDb id ----
            String indiaUpdate = "UPDATE " + INDIA_TABLE + " SET directors = ? WHERE id = ? AND COALESCE(directors, '') = ''";
            Map<String, Film> india = missingFilms(conn, "SELECT id, movie_name, release_date, language FROM " + INDIA_TABLE +
                " WHERE COALESCE(directors, '') = '' AND id LIKE 'tt%'");
            log(String.format("%s: %,d movie(s) without directors.", INDIA_TABLE, india.size()));

            Map<String, String> byImdb = wikidata.directorsByImdbId(india.keySet());
            int rows = update(conn, indiaUpdate, byImdb, false);
            log(String.format("%s: Wikidata (by IMDb id) filled %,d movie(s) / %,d row(s).", INDIA_TABLE, byImdb.size(), rows));
            byImdb.keySet().forEach(india::remove);

            Map<String, String> fromWikipedia = lookUpOnWikipedia(wikipedia, india.values());
            rows = update(conn, indiaUpdate, fromWikipedia, false);
            log(String.format("%s: Wikipedia film lists filled %,d movie(s) / %,d row(s); %,d still without directors.",
                INDIA_TABLE, fromWikipedia.size(), rows, india.size() - fromWikipedia.size()));

            // ---- actors_data_collection, keyed by tmdb_movie_id ----
            String actorsUpdate = "UPDATE " + ACTORS_TABLE + " SET director = ? WHERE tmdb_movie_id = ? AND COALESCE(director, '') = ''";
            Map<String, Film> actors = missingFilms(conn, "SELECT tmdb_movie_id::text, movie_name, release_date, language FROM " +
                ACTORS_TABLE + " WHERE tmdb_movie_id IS NOT NULL AND COALESCE(director, '') = ''");
            log(String.format("%s: %,d movie(s) without director.", ACTORS_TABLE, actors.size()));

            Map<Integer, String> byTmdb = wikidata.directorsByTmdbId(actors.keySet().stream().map(Integer::valueOf).toList());
            Map<String, String> byTmdbKey = new HashMap<>();
            byTmdb.forEach((id, d) -> byTmdbKey.put(String.valueOf(id), d));
            rows = update(conn, actorsUpdate, byTmdbKey, true);
            log(String.format("%s: Wikidata (by TMDB id) filled %,d movie(s) / %,d row(s).", ACTORS_TABLE, byTmdbKey.size(), rows));
            byTmdbKey.keySet().forEach(actors::remove);

            fromWikipedia = lookUpOnWikipedia(wikipedia, actors.values());
            rows = update(conn, actorsUpdate, fromWikipedia, true);
            log(String.format("%s: Wikipedia film lists filled %,d movie(s) / %,d row(s); %,d still without director.",
                ACTORS_TABLE, fromWikipedia.size(), rows, actors.size() - fromWikipedia.size()));
        }
    }

    /** Groups the query's (key, movie_name, release_date, language) rows by key — one film may be stored under several titles. */
    private static Map<String, Film> missingFilms(Connection conn, String sql) throws SQLException {
        Map<String, Film> films = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1);
                String date = rs.getString(3);
                Integer year = date != null && date.matches("^\\d{4}.*") ? Integer.valueOf(date.substring(0, 4)) : null;
                String language = rs.getString(4) == null ? "" : rs.getString(4).trim().toLowerCase();
                if (year == null) continue;
                LocalDate releaseDate = date.matches("^\\d{4}-\\d{2}-\\d{2}$") ? LocalDate.parse(date) : null;
                films.computeIfAbsent(key, k -> new Film(k, new LinkedHashSet<>(), year, releaseDate, language))
                    .titles().add(rs.getString(2));
            }
        }
        return films;
    }

    /** Key -> directors for every film Wikipedia's yearly lists identify unambiguously (see class javadoc). */
    Map<String, String> lookUpOnWikipedia(WikipediaFilmListClient wikipedia, Iterable<Film> films) {
        Map<String, String> found = new HashMap<>();
        int checked = 0;
        for (Film film : films) {
            if (++checked % 100 == 0) log(String.format("Wikipedia: checked %,d film(s), found %,d so far...", checked, found.size()));
            String language = WIKIPEDIA_LANGUAGES.get(film.language());
            if (language == null) continue;  // e.g. English/unknown — no per-language Wikipedia list
            for (int year : film.yearsToSearch()) {
                Set<String> directors = new LinkedHashSet<>();
                try {
                    for (WikipediaFilmListClient.FilmEntry entry : wikipedia.films(language, year)) {
                        if (film.titles().stream().anyMatch(t -> TitleMatcher.matches(t, entry.title()))) {
                            directors.add(entry.directors());
                        }
                    }
                } catch (Exception e) {
                    log(String.format("Wikipedia: 'List of %s films of %d' unavailable (%s) — skipping that page.",
                        language, year, e.getMessage()));
                    continue;
                }
                if (directors.isEmpty()) continue;
                if (directors.size() == 1) found.put(film.key(), directors.iterator().next());
                break;  // matched on this page — ambiguous or not, don't look at neighbouring years
            }
        }
        return found;
    }

    private static int update(Connection conn, String sql, Map<String, String> directorsByKey, boolean intKey) throws SQLException {
        int rows = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : directorsByKey.entrySet()) {
                ps.setString(1, e.getValue());
                if (intKey) ps.setInt(2, Integer.parseInt(e.getKey()));
                else ps.setString(2, e.getKey());
                rows += ps.executeUpdate();
            }
        }
        return rows;
    }

    // ---- helpers ----

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
    }

    private Properties loadProperties(String resourceName) {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) throw new RuntimeException(resourceName + " not found on classpath");
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load " + resourceName, e);
        }
        return props;
    }
}
