package com.lit.fire.flame.indiamovies;

import com.lit.fire.flame.moviedetails.TmdbClient;
import com.lit.fire.flame.moviedetails.TmdbMovieCredits;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One-shot fill (--india-cast-crew-fill-once [all]) of india_movies_data_collection's
 * lead_actor_1..lead_actor_5, director and music_director columns (created here if missing).
 *
 * Every movie is keyed by its IMDb id (the table's {@code id} column). Sources, in order:
 * <ol>
 *   <li>TMDB — IMDb id resolved exactly via /find, then the movie's credits: top 5 billed cast,
 *       directors, music director (same rules as the actors_data_collection import, see
 *       TmdbClient#fetchMovieCredits).</li>
 *   <li>actors_data_collection — the rows for the same TMDB movie id, else same title + release
 *       year + language; leads by role_position.</li>
 *   <li>Wikidata, by exact IMDb id (see WikidataCastCrewClient), only when its item's release
 *       year is within a year of ours.</li>
 * </ol>
 * The music director found is then checked against the English Wikipedia infobox (see
 * WikipediaMusicClient), same year check: when it isn't among the film's songs composers there
 * (TMDB often lists a background-score composer first), the first songs composer replaces it.
 * The leads come as a set from the first source that has any (sources are never mixed, since
 * each orders its cast differently); director and music_director are taken field by field from
 * the first source that has them. Each processed row is stamped with cast_crew_last_checked;
 * without "all" only rows never processed are looked at, so new movies get filled on a re-run.
 */
public class IndiaCastCrewFillService {

    private static final String PREFIX = "[INDIA-CAST-CREW] ";
    private static final String TABLE = "india_movies_data_collection";
    private static final String ACTORS_TABLE = "actors_data_collection";
    private static final int LEAD_COUNT = 5;

    /** One film to fill: IMDb id plus every (title, year, language) it's stored under. */
    record Film(String imdbId, Set<String> titles, String year, String language) {}

    /** Values found so far for one film; leads is empty until a source supplies them. */
    static final class Found {
        Integer tmdbId;
        List<String> leads = List.of();
        /** Every TMDB music-director credit, for spelling Wikipedia's names the way TMDB does. */
        List<String> tmdbComposers = List.of();
        String director, musicDirector;
        String leadsSource, directorSource, musicSource;

        boolean complete() { return !leads.isEmpty() && director != null && musicDirector != null; }

        void offer(String source, List<String> newLeads, String newDirector, String newMusic) {
            if (leads.isEmpty() && newLeads != null && !newLeads.isEmpty()) {
                leads = newLeads.subList(0, Math.min(LEAD_COUNT, newLeads.size()));
                leadsSource = source;
            }
            if (director == null && notBlank(newDirector)) { director = newDirector.trim(); directorSource = source; }
            if (musicDirector == null && notBlank(newMusic)) { musicDirector = newMusic.trim(); musicSource = source; }
        }
    }

    public void runOnce(boolean all) throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String bearerToken = secrets.getProperty("tmdb.api.read.access.token", "");
        String apiKeyV3    = secrets.getProperty("tmdb.api.key", "");
        int    tmdbTimeoutSec = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec   = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        int    concurrency    = Integer.parseInt(config.getProperty("indiamovies.concurrency", "20"));
        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, tmdbTimeoutSec, 0.0, maxReqPerSec);

        try (Connection conn = DriverManager.getConnection(secrets.getProperty("db.url"),
                secrets.getProperty("db.user"), secrets.getProperty("db.password", ""))) {
            ensureColumns(conn);

            Map<String, Film> films = loadFilms(conn, all);
            log(String.format("=== %,d movie(s) to fill (%s) ===", films.size(), all ? "all rows" : "never checked"));
            Map<String, Found> found = new LinkedHashMap<>();
            films.keySet().forEach(id -> found.put(id, new Found()));

            // 1. TMDB
            if (tmdb.isConfigured()) {
                ExecutorService executor = Executors.newFixedThreadPool(concurrency);
                try {
                    Map<String, Future<TmdbMovieCredits>> futures = new LinkedHashMap<>();
                    for (Film film : films.values()) {
                        if (!film.imdbId().startsWith("tt")) continue;
                        futures.put(film.imdbId(), executor.submit(() -> {
                            Integer tmdbId = tmdb.findMovieIdByImdbId(film.imdbId());
                            return tmdbId == null ? null : tmdb.fetchMovieCredits(tmdbId, LEAD_COUNT);
                        }));
                    }
                    int done = 0, errors = 0;
                    for (Map.Entry<String, Future<TmdbMovieCredits>> e : futures.entrySet()) {
                        try {
                            TmdbMovieCredits m = e.getValue().get();
                            if (m != null) {
                                Found f = found.get(e.getKey());
                                f.tmdbId = m.movieId();
                                if (m.musicComposers() != null) {
                                    f.tmdbComposers = java.util.Arrays.stream(m.musicComposers().split(","))
                                        .map(String::trim).filter(IndiaCastCrewFillService::notBlank).toList();
                                }
                                f.offer("tmdb", m.leads().stream().map(TmdbMovieCredits.Lead::name).toList(),
                                    m.directors(), m.musicDirectors());
                            }
                        } catch (Exception ex) {
                            errors++;
                            logErr(String.format("TMDB failed for %s: %s", e.getKey(), ex.getMessage()));
                        }
                        if (++done % 250 == 0) log(String.format("TMDB: %,d/%,d done...", done, futures.size()));
                    }
                    log(String.format("TMDB: %,d movie(s) matched, %,d error(s).",
                        found.values().stream().filter(f -> f.tmdbId != null).count(), errors));
                } finally {
                    executor.shutdownNow();
                }
            } else {
                logErr("No TMDB credential in secrets.properties — skipping TMDB.");
            }

            // 2. actors_data_collection
            int fromActors = 0;
            for (Film film : films.values()) {
                Found f = found.get(film.imdbId());
                if (f.complete()) continue;
                if (fillFromActorsTable(conn, film, f)) fromActors++;
            }
            log(String.format("actors_data_collection: contributed to %,d movie(s).", fromActors));

            // 3. Wikidata
            List<String> stillMissing = films.keySet().stream()
                .filter(id -> id.startsWith("tt") && !found.get(id).complete()).toList();
            if (!stillMissing.isEmpty()) {
                try {
                    Map<String, WikidataCastCrewClient.Credits> wd = new WikidataCastCrewClient().creditsByImdbId(stillMissing);
                    int used = 0, wrongYear = 0;
                    for (Map.Entry<String, WikidataCastCrewClient.Credits> e : wd.entrySet()) {
                        WikidataCastCrewClient.Credits c = e.getValue();
                        // Wrong IMDb ids exist on Wikidata — only trust an item released within a year of ours.
                        String year = films.get(e.getKey()).year();
                        if (c.year() == null || year.isEmpty() || Math.abs(c.year() - Integer.parseInt(year)) > 1) {
                            wrongYear++;
                            continue;
                        }
                        found.get(e.getKey()).offer("wikidata", c.cast(),
                            c.directors().isEmpty() ? null : String.join(", ", c.directors()),
                            c.composers().isEmpty() ? null : canonicalTeamName(c.composers().get(0)));
                        used++;
                    }
                    log(String.format("Wikidata: %,d of %,d incomplete movie(s) found, %,d used, %,d skipped (year mismatch or no year).",
                        wd.size(), stillMissing.size(), used, wrongYear));
                } catch (Exception e) {
                    logErr("Wikidata lookup failed: " + e.getMessage());
                }
            }

            // 4. Wikipedia check of the music director
            List<String> withImdbId = films.keySet().stream().filter(id -> id.startsWith("tt")).toList();
            if (!withImdbId.isEmpty()) {
                try {
                    Map<String, WikipediaMusicClient.Music> wiki = new WikipediaMusicClient().musicByImdbId(withImdbId);
                    Map<String, String> spellings = knownMusicSpellings(conn);
                    int kept = 0, changed = 0, wrongYear = 0;
                    for (Map.Entry<String, WikipediaMusicClient.Music> e : wiki.entrySet()) {
                        WikipediaMusicClient.Music music = e.getValue();
                        String year = films.get(e.getKey()).year();
                        if (music.year() == null || year.isEmpty() || Math.abs(music.year() - Integer.parseInt(year)) > 1) {
                            wrongYear++;
                            continue;
                        }
                        Found f = found.get(e.getKey());
                        String chosen = WikipediaMusicClient.chooseMusicDirector(f.musicDirector, music, f.tmdbComposers, spellings);
                        if (chosen != null && !chosen.equals(f.musicDirector)) {
                            f.musicDirector = chosen;
                            f.musicSource = "wikipedia";
                            changed++;
                        } else {
                            kept++;
                        }
                    }
                    log(String.format("Wikipedia: %,d of %,d movie(s) have infobox music, %,d music director(s) confirmed, %,d set, %,d skipped (year mismatch or no year).",
                        wiki.size(), withImdbId.size(), kept, changed, wrongYear));
                } catch (Exception e) {
                    logErr("Wikipedia music check failed (music directors left as found): " + e.getMessage());
                }
            }

            int rows = save(conn, found);
            log(String.format("=== Done — %,d row(s) updated. Leads: %s; director: %s; music director: %s ===", rows,
                countBySource(found, f -> f.leadsSource), countBySource(found, f -> f.directorSource),
                countBySource(found, f -> f.musicSource)));
        }
    }

    // ---- actors_data_collection fallback ----

    private static boolean fillFromActorsTable(Connection conn, Film film, Found f) throws SQLException {
        String select = "SELECT actor_name, role_position, director, music_director, tmdb_movie_id FROM " + ACTORS_TABLE;
        List<Object[]> rows = new ArrayList<>();
        if (f.tmdbId != null) {
            rows = query(conn, select + " WHERE tmdb_movie_id = ?", ps -> ps.setInt(1, f.tmdbId));
        }
        if (rows.isEmpty()) {
            for (String title : film.titles()) {
                rows.addAll(query(conn, select + " WHERE lower(trim(movie_name)) = lower(trim(?)) AND left(release_date, 4) = ?" +
                        " AND (COALESCE(language, '') IN ('', 'Unknown') OR ? LIKE '%' || lower(trim(language)) || '%')",
                    ps -> { ps.setString(1, title); ps.setString(2, film.year()); ps.setString(3, film.language()); }));
            }
            // Two different TMDB films under one title/year (e.g. same-titled remakes) — ambiguous, skip.
            if (rows.stream().map(r -> r[4]).filter(id -> id != null).distinct().count() > 1) return false;
        }
        if (rows.isEmpty()) return false;

        // Leads by billing position; a TMDB-sourced row wins over another source's row at the same position.
        rows.sort((a, b) -> {
            int pa = a[1] == null ? Integer.MAX_VALUE : (Integer) a[1], pb = b[1] == null ? Integer.MAX_VALUE : (Integer) b[1];
            if (pa != pb) return Integer.compare(pa, pb);
            return Boolean.compare(a[4] == null, b[4] == null);
        });
        Set<Integer> positions = new LinkedHashSet<>();
        Set<String> leads = new LinkedHashSet<>();
        for (Object[] r : rows) {
            if (r[1] == null || !positions.add((Integer) r[1])) continue;
            leads.add((String) r[0]);
        }
        String director = rows.stream().map(r -> (String) r[2]).filter(IndiaCastCrewFillService::notBlank).findFirst().orElse(null);
        String music = rows.stream().map(r -> (String) r[3]).filter(IndiaCastCrewFillService::notBlank).findFirst().orElse(null);

        boolean hadLeads = !f.leads.isEmpty(), hadDirector = f.director != null, hadMusic = f.musicDirector != null;
        f.offer("actors", new ArrayList<>(leads), director, music);
        return hadLeads != !f.leads.isEmpty() || hadDirector != (f.director != null) || hadMusic != (f.musicDirector != null);
    }

    /** Normalised name -> the most used capitalised spelling among the music credits already stored. */
    private static Map<String, String> knownMusicSpellings(Connection conn) throws SQLException {
        String sql = "SELECT name, count(*) FROM (" +
            " SELECT trim(music_director) AS name FROM " + ACTORS_TABLE +
            " UNION ALL SELECT trim(c) FROM " + ACTORS_TABLE + ", unnest(string_to_array(music_composers, ',')) c" +
            " UNION ALL SELECT trim(music_director) FROM " + TABLE + ") x" +
            " WHERE name ~ '^[[:upper:]]' GROUP BY name ORDER BY count(*) DESC";
        Map<String, String> spellings = new java.util.HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) spellings.putIfAbsent(WikipediaMusicClient.normalize(rs.getString(1)), rs.getString(1));
        }
        return spellings;
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private static List<Object[]> query(Connection conn, String sql, Binder binder) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[] { rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5) });
                }
            }
        }
        return rows;
    }

    // ---- DB ----

    private static void ensureColumns(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (int i = 1; i <= LEAD_COUNT; i++) {
                st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS lead_actor_" + i + " TEXT");
            }
            st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS director TEXT");
            st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS music_director TEXT");
            st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS cast_crew_last_checked TEXT");
        }
    }

    private static Map<String, Film> loadFilms(Connection conn, boolean all) throws SQLException {
        String sql = "SELECT id, movie_name, release_date, language FROM " + TABLE + " WHERE COALESCE(id, '') <> ''" +
            (all ? "" : " AND cast_crew_last_checked IS NULL") + " ORDER BY release_date";
        Map<String, Film> films = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String date = rs.getString(3);
                String language = rs.getString(4) == null ? "" : rs.getString(4).trim().toLowerCase();
                films.computeIfAbsent(rs.getString(1), id -> new Film(id, new LinkedHashSet<>(),
                        date == null || date.length() < 4 ? "" : date.substring(0, 4), language))
                    .titles().add(rs.getString(2));
            }
        }
        return films;
    }

    private static int save(Connection conn, Map<String, Found> found) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET lead_actor_1 = ?, lead_actor_2 = ?, lead_actor_3 = ?, lead_actor_4 = ?," +
            " lead_actor_5 = ?, director = ?, music_director = ?, cast_crew_last_checked = ? WHERE id = ?";
        String today = LocalDate.now().toString();
        int rows = 0;
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Found> e : found.entrySet()) {
                Found f = e.getValue();
                for (int i = 0; i < LEAD_COUNT; i++) ps.setString(i + 1, i < f.leads.size() ? f.leads.get(i) : null);
                ps.setString(6, f.director);
                ps.setString(7, f.musicDirector);
                ps.setString(8, today);
                ps.setString(9, e.getKey());
                ps.addBatch();
            }
            for (int n : ps.executeBatch()) rows += Math.max(n, 0);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
        return rows;
    }

    // ---- helpers ----

    private static String countBySource(Map<String, Found> found, java.util.function.Function<Found, String> source) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        found.values().forEach(f -> counts.merge(String.valueOf(source.apply(f)).replace("null", "none"), 1L, Long::sum));
        return counts.toString();
    }

    /** Wikidata's "Shankar–Ehsaan–Loy" -> "Shankar Ehsan Loy", the spelling TMDB-sourced rows use (see TmdbClient). */
    private static String canonicalTeamName(String composer) {
        return TmdbClient.normalize(composer).matches("shankar ?ehs?aan ?loy") ? "Shankar Ehsan Loy" : composer;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
