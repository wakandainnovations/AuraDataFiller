package com.lit.fire.flame.actor;

import com.lit.fire.flame.moviedetails.TmdbClient;
import com.lit.fire.flame.moviedetails.TmdbMovieCredits;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One-shot import (--filmography-tmdb-import-once) of the complete TMDB filmographies of everyone
 * named in india_movies_data_collection's lead_actor_1..5, director and music_director into
 * actors_data_collection, so the *_movie_count columns (IndiaMovieCountFillService) see every
 * earlier film — not only the films where the person was among the top 5 billed of an India-origin
 * TMDB movie, which is all the lead-cast import (TmdbActorImportService) stores.
 *
 * <ol>
 *   <li>Person ids: each India movie is looked up on TMDB by its IMDb id; its lead / director /
 *       music-director names are matched to the movie's credited people by name (exact after
 *       normalisation, else close spelling). A music team name ("Vishal–Shekhar") with no exact
 *       match takes the credited composers whose first name is part of it.</li>
 *   <li>Each person's TMDB filmography: acting credits for leads (except "Himself"/"Herself"
 *       appearances), Director credits for directors, music-director jobs for music directors —
 *       released films only (dated, not after today).</li>
 *   <li>Every such film not yet imported is fetched and saved like the lead-cast import (top
 *       {@code actors.tmdb.lead.count} billed, each row carrying the film's director and music
 *       director); an actor billed below that also gets their own row with their billing
 *       position. Saves go through TmdbActorDatabaseService, so its matching rules apply (an
 *       existing row for the same film is updated and tagged, not duplicated).</li>
 * </ol>
 * Inserted rows use the actor name already on file for that TMDB person, if any, so earlier name
 * clean-ups are kept. Films without any cast can't be stored (actor_name is required) and are
 * only counted in the log. Safe to re-run: films already imported are skipped.
 */
public class TmdbFilmographyImportService {

    private static final String PREFIX = "[FILMOGRAPHY-TMDB] ";
    private static final String INDIA_TABLE = "india_movies_data_collection";
    private static final String ACTORS_TABLE = "actors_data_collection";
    private static final Pattern SELF_ROLE = Pattern.compile("\\b(him|her|them)self\\b|^self\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_SEPARATOR = Pattern.compile("[-–&]");
    private static final double NAME_SIMILARITY = 0.85;

    enum Role { LEAD, DIRECTOR, MUSIC }

    /** One India movie's people as named in the India table. */
    private record IndiaMovie(String imdbId, List<String> leads, List<String> directors, String music) {}

    /** A film to fetch and save, plus the actors billed below the top leads who need their own row in it. */
    private static final class FilmToSave {
        final Map<Integer, TmdbClient.FilmographyCredit> extraActors = new LinkedHashMap<>();
        boolean saveLeads;
    }

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        String bearerToken = secrets.getProperty("tmdb.api.read.access.token", "");
        String apiKeyV3    = secrets.getProperty("tmdb.api.key", "");
        if (bearerToken.isBlank() && apiKeyV3.isBlank()) {
            logErr("Neither tmdb.api.read.access.token nor tmdb.api.key is set in secrets.properties — aborting.");
            return;
        }
        String dbUrl = secrets.getProperty("db.url"), dbUser = secrets.getProperty("db.user"),
            dbPassword = secrets.getProperty("db.password", "");
        int    timeoutSec   = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        int    concurrency  = Integer.parseInt(config.getProperty("actors.tmdb.concurrency", "20"));
        int    leadCount    = Integer.parseInt(config.getProperty("actors.tmdb.lead.count", "3"));
        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, timeoutSec, 0.0, maxReqPerSec);
        String today = LocalDate.now().toString();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             TmdbActorDatabaseService db = new TmdbActorDatabaseService(dbUrl, dbUser, dbPassword)) {
            db.ensureSchema();

            // 1. person ids
            List<IndiaMovie> india = loadIndiaMovies(conn);
            log(String.format("=== Resolving TMDB people for %,d India movie(s) ===", india.size()));
            Map<Integer, Set<Role>> people = new HashMap<>();
            int[] unresolved = new int[3];
            List<Future<TmdbClient.MoviePeople>> peopleFutures = new ArrayList<>();
            for (IndiaMovie m : india) {
                peopleFutures.add(executor.submit(() -> {
                    Integer id = tmdb.findMovieIdByImdbId(m.imdbId());
                    return id == null ? null : tmdb.fetchMoviePeople(id);
                }));
            }
            int notOnTmdb = 0;
            for (int i = 0; i < india.size(); i++) {
                TmdbClient.MoviePeople mp = getOrLog(peopleFutures.get(i), india.get(i).imdbId());
                if (mp == null) { notOnTmdb++; continue; }
                IndiaMovie m = india.get(i);
                for (String lead : m.leads()) addAll(people, Role.LEAD, match(lead, mp.cast(), false), unresolved, 0);
                for (String d : m.directors()) addAll(people, Role.DIRECTOR, match(d, mp.directors(), false), unresolved, 1);
                if (m.music() != null) addAll(people, Role.MUSIC, match(m.music(), mp.composers(), true), unresolved, 2);
            }
            log(String.format("%,d distinct people (%,d India movies not on TMDB; unmatched names: %,d leads, %,d directors, %,d music)",
                people.size(), notOnTmdb, unresolved[0], unresolved[1], unresolved[2]));

            // 2. filmographies
            Map<Integer, Future<TmdbClient.PersonFilmography>> filmFutures = new LinkedHashMap<>();
            for (int pid : people.keySet()) filmFutures.put(pid, executor.submit(() -> tmdb.fetchPersonFilmography(pid)));
            Map<Integer, TmdbClient.PersonFilmography> persons = new HashMap<>();
            Map<Integer, FilmToSave> films = new LinkedHashMap<>();
            int done = 0;
            for (Map.Entry<Integer, Future<TmdbClient.PersonFilmography>> e : filmFutures.entrySet()) {
                TmdbClient.PersonFilmography pf = getOrLog(e.getValue(), "person " + e.getKey());
                if (++done % 500 == 0) log(String.format("Filmographies: %,d/%,d fetched...", done, filmFutures.size()));
                if (pf == null) continue;
                persons.put(pf.personId(), pf);
                Set<Role> roles = people.get(pf.personId());
                if (roles.contains(Role.LEAD)) {
                    for (TmdbClient.FilmographyCredit c : pf.cast()) {
                        if (!released(c, today) || (c.character() != null && SELF_ROLE.matcher(c.character()).find())) continue;
                        FilmToSave f = films.computeIfAbsent(c.movieId(), k -> new FilmToSave());
                        f.saveLeads = true;
                        if (c.order() == null || c.order() >= leadCount) f.extraActors.putIfAbsent(pf.personId(), c);
                    }
                }
                for (TmdbClient.FilmographyCredit c : pf.crew()) {
                    boolean wanted = (roles.contains(Role.DIRECTOR) && "Director".equals(c.job()))
                        || (roles.contains(Role.MUSIC) && TmdbClient.isMusicDirectorJob(c.job()));
                    if (wanted && released(c, today)) films.computeIfAbsent(c.movieId(), k -> new FilmToSave()).saveLeads = true;
                }
            }

            // 3. skip what's already on file
            Set<Integer> imported = new HashSet<>();
            Set<Long> personMovieRows = new HashSet<>();
            Map<Integer, String> nameOnFile = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT tmdb_person_id, tmdb_movie_id, actor_name FROM " + ACTORS_TABLE +
                     " WHERE tmdb_movie_id IS NOT NULL")) {
                Map<Integer, Map<String, Integer>> names = new HashMap<>();
                while (rs.next()) {
                    int mid = rs.getInt(2);
                    imported.add(mid);
                    Integer pid = (Integer) rs.getObject(1);
                    if (pid == null) continue;
                    personMovieRows.add(key(pid, mid));
                    names.computeIfAbsent(pid, k -> new HashMap<>()).merge(rs.getString(3), 1, Integer::sum);
                }
                names.forEach((pid, counts) -> nameOnFile.put(pid,
                    counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey()));
            }
            films.forEach((mid, f) -> {
                if (imported.contains(mid)) f.saveLeads = false;
                f.extraActors.keySet().removeIf(pid -> personMovieRows.contains(key(pid, mid)));
            });
            films.values().removeIf(f -> !f.saveLeads && f.extraActors.isEmpty());
            log(String.format("=== %,d film(s) to fetch and save (%,d new to the table) ===",
                films.size(), films.values().stream().filter(f -> f.saveLeads).count()));

            // 4. fetch + save
            Map<Integer, Future<TmdbMovieCredits>> movieFutures = new LinkedHashMap<>();
            for (int mid : films.keySet()) movieFutures.put(mid, executor.submit(() -> tmdb.fetchMovieCredits(mid, leadCount)));
            Map<TmdbActorDatabaseService.SaveResult, Integer> counts = new HashMap<>();
            int saved = 0, noCast = 0, unavailable = 0, errors = 0;
            done = 0;
            for (Map.Entry<Integer, Future<TmdbMovieCredits>> e : movieFutures.entrySet()) {
                if (++done % 1000 == 0) log(String.format("Films: %,d/%,d saved...", done, movieFutures.size()));
                TmdbMovieCredits m = getOrLog(e.getValue(), "movie " + e.getKey());
                if (m == null) { unavailable++; continue; }
                FilmToSave f = films.get(e.getKey());
                try {
                    if (f.saveLeads) {
                        if (m.leads().isEmpty()) noCast++;
                        for (TmdbMovieCredits.Lead lead : m.leads()) {
                            TmdbClient.PersonFilmography pf = persons.get(lead.personId());
                            counts.merge(db.saveLead(m, renamed(lead, nameOnFile), pf != null ? pf.birthday() : null), 1, Integer::sum);
                        }
                        db.markMovie(m.movieId(), today);
                    }
                    for (Map.Entry<Integer, TmdbClient.FilmographyCredit> a : f.extraActors.entrySet()) {
                        TmdbClient.PersonFilmography pf = persons.get(a.getKey());
                        TmdbClient.FilmographyCredit c = a.getValue();
                        TmdbMovieCredits.Lead lead = new TmdbMovieCredits.Lead(pf.personId(), pf.name(), pf.gender(),
                            // TMDB always sends an order for acting credits; a missing one sorts after the leads
                            c.order() != null ? c.order() + 1 : leadCount + 1, c.character());
                        counts.merge(db.saveLead(m, renamed(lead, nameOnFile), pf.birthday()), 1, Integer::sum);
                    }
                    saved++;
                } catch (SQLException ex) {
                    errors++;
                    logErr(String.format("'%s' (%d) — save error: %s", m.title(), m.movieId(), ex.getMessage()));
                }
            }
            log(String.format("=== Done — films saved: %,d (%,d without any cast) | rows inserted: %,d | updated: %,d | " +
                    "unchanged: %,d | PK conflicts: %,d | not fetched (no title/date or TMDB error): %,d | save errors: %,d ===",
                saved, noCast, counts.getOrDefault(TmdbActorDatabaseService.SaveResult.INSERTED, 0),
                counts.getOrDefault(TmdbActorDatabaseService.SaveResult.UPDATED, 0),
                counts.getOrDefault(TmdbActorDatabaseService.SaveResult.UNCHANGED, 0),
                counts.getOrDefault(TmdbActorDatabaseService.SaveResult.CONFLICT, 0), unavailable, errors));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static List<IndiaMovie> loadIndiaMovies(Connection conn) throws SQLException {
        Map<String, IndiaMovie> byId = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, lead_actor_1, lead_actor_2, lead_actor_3, lead_actor_4, lead_actor_5," +
                 " director, music_director FROM " + INDIA_TABLE + " WHERE id LIKE 'tt%'")) {
            while (rs.next()) {
                List<String> leads = new ArrayList<>(), directors = new ArrayList<>();
                for (int i = 2; i <= 6; i++) if (notBlank(rs.getString(i))) leads.add(rs.getString(i).trim());
                if (notBlank(rs.getString(7))) {
                    for (String d : rs.getString(7).split(",")) if (notBlank(d)) directors.add(d.trim());
                }
                String music = notBlank(rs.getString(8)) ? rs.getString(8).trim() : null;
                // dubbed versions share an IMDb id; the first row's names stand for all of them
                byId.putIfAbsent(rs.getString(1), new IndiaMovie(rs.getString(1), leads, directors, music));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * The credited people {@code name} refers to: the exact (normalised) match, else the single
     * closest spelling; for a music team name, the composers whose first name is part of it.
     */
    static List<TmdbClient.CreditedPerson> match(String name, List<TmdbClient.CreditedPerson> credited, boolean musicTeam) {
        String n = TmdbClient.normalize(name).replace(" ", "");
        List<TmdbClient.CreditedPerson> exact = credited.stream()
            .filter(p -> TmdbClient.normalize(p.name()).replace(" ", "").equals(n)).limit(1).toList();
        if (!exact.isEmpty()) return exact;
        TmdbClient.CreditedPerson best = null;
        double bestScore = NAME_SIMILARITY;
        for (TmdbClient.CreditedPerson p : credited) {
            double s = TmdbClient.similarity(name, p.name());
            if (s >= bestScore) { bestScore = s; best = p; }
        }
        if (best != null) return List.of(best);
        if (musicTeam && TEAM_SEPARATOR.matcher(name).find()) {
            Set<String> teamWords = Set.of(TmdbClient.normalize(name.replaceAll("[-–&]", " ")).split(" "));
            Set<TmdbClient.CreditedPerson> members = new LinkedHashSet<>();
            for (TmdbClient.CreditedPerson p : credited) {
                String first = TmdbClient.normalize(p.name()).split(" ")[0];
                if (teamWords.contains(first)) members.add(p);
            }
            return new ArrayList<>(members);
        }
        return List.of();
    }

    private static void addAll(Map<Integer, Set<Role>> people, Role role, List<TmdbClient.CreditedPerson> matched,
                               int[] unresolved, int slot) {
        if (matched.isEmpty()) unresolved[slot]++;
        for (TmdbClient.CreditedPerson p : matched) people.computeIfAbsent(p.personId(), k -> new HashSet<>()).add(role);
    }

    private static boolean released(TmdbClient.FilmographyCredit c, String today) {
        return c.releaseDate() != null && c.releaseDate().compareTo(today) <= 0;
    }

    /** {@code lead} under the actor name already on file for that TMDB person, if any. */
    private static TmdbMovieCredits.Lead renamed(TmdbMovieCredits.Lead lead, Map<Integer, String> nameOnFile) {
        String name = nameOnFile.get(lead.personId());
        return name == null || name.equals(lead.name()) ? lead
            : new TmdbMovieCredits.Lead(lead.personId(), name, lead.gender(), lead.rolePosition(), lead.character());
    }

    private static long key(int personId, int movieId) {
        return ((long) personId << 32) | (movieId & 0xffffffffL);
    }

    private <T> T getOrLog(Future<T> future, String what) {
        try {
            return future.get();
        } catch (Exception e) {
            logErr(what + " — TMDB error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return null;
        }
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
