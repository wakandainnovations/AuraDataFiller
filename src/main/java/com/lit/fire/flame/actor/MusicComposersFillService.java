package com.lit.fire.flame.actor;

import com.lit.fire.flame.moviedetails.TmdbClient;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One-shot backfill (--music-composers-fill-once) of actors_data_collection.music_composers:
 * every name TMDB credits under a music-director job (TmdbClient#fetchMusicComposers) for each
 * TMDB-tagged film, written to all of that film's rows. music_director keeps its single name;
 * music_composers exists so a duo member TMDB credits second (Pyarelal, Jigar Saraiya) still gets
 * the film counted (see IndiaMovieCountFillService). The TMDB imports fill it for new rows.
 *
 * Films are recorded in tmdb_music_composer_scan once fetched (even when TMDB credits no music),
 * so an interrupted run resumes where it stopped.
 */
public class MusicComposersFillService {

    private static final String PREFIX = "[MUSIC-COMPOSERS] ";
    private static final String TABLE = "actors_data_collection";
    private static final String SCAN_TABLE = "tmdb_music_composer_scan";

    private record Outcome(int movieId, String composers, Exception error) {}

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);
        String bearerToken = secrets.getProperty("tmdb.api.read.access.token", "");
        String apiKeyV3    = secrets.getProperty("tmdb.api.key", "");
        if (bearerToken.isBlank() && apiKeyV3.isBlank()) {
            logErr("Neither tmdb.api.read.access.token nor tmdb.api.key is set in secrets.properties — aborting.");
            return;
        }
        int    timeoutSec   = Integer.parseInt(config.getProperty("tmdb.request.timeout.seconds", "15"));
        double maxReqPerSec = Double.parseDouble(config.getProperty("tmdb.rate.limit.requests.per.second", "35"));
        int    concurrency  = Integer.parseInt(config.getProperty("actors.tmdb.concurrency", "20"));
        TmdbClient tmdb = new TmdbClient(bearerToken, apiKeyV3, timeoutSec, 0.0, maxReqPerSec);
        String today = LocalDate.now().toString();

        try (Connection conn = DriverManager.getConnection(secrets.getProperty("db.url"),
                secrets.getProperty("db.user"), secrets.getProperty("db.password", ""))) {
            List<Integer> pending = new ArrayList<>();
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS music_composers TEXT");
                st.execute("CREATE INDEX IF NOT EXISTS idx_actors_data_tmdb_movie ON " + TABLE + " (tmdb_movie_id)");
                st.execute("CREATE TABLE IF NOT EXISTS " + SCAN_TABLE + " (tmdb_movie_id INTEGER PRIMARY KEY, last_checked DATE NOT NULL)");
                try (ResultSet rs = st.executeQuery("SELECT DISTINCT tmdb_movie_id FROM " + TABLE + " a WHERE tmdb_movie_id IS NOT NULL" +
                        " AND NOT EXISTS (SELECT 1 FROM " + SCAN_TABLE + " s WHERE s.tmdb_movie_id = a.tmdb_movie_id)")) {
                    while (rs.next()) pending.add(rs.getInt(1));
                }
            }
            log(String.format("=== %,d TMDB film(s) to fetch music composers for ===", pending.size()));

            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            int withComposers = 0, errors = 0;
            try (PreparedStatement update = conn.prepareStatement(
                     "UPDATE " + TABLE + " SET music_composers = ? WHERE tmdb_movie_id = ?");
                 PreparedStatement mark = conn.prepareStatement(
                     "INSERT INTO " + SCAN_TABLE + " (tmdb_movie_id, last_checked) VALUES (?, ?::date)" +
                     " ON CONFLICT (tmdb_movie_id) DO UPDATE SET last_checked = EXCLUDED.last_checked")) {
                CompletionService<Outcome> cs = new ExecutorCompletionService<>(executor);
                for (int id : pending) {
                    cs.submit(() -> {
                        try {
                            return new Outcome(id, tmdb.fetchMusicComposers(id), null);
                        } catch (Exception e) {
                            return new Outcome(id, null, e);
                        }
                    });
                }
                for (int done = 1; done <= pending.size(); done++) {
                    Outcome o = cs.take().get();
                    if (o.error() != null) {
                        errors++;
                        logErr(String.format("movie %d — TMDB error: %s", o.movieId(), o.error().getMessage()));
                        continue;
                    }
                    if (o.composers() != null) {
                        withComposers++;
                        update.setString(1, o.composers());
                        update.setInt(2, o.movieId());
                        update.executeUpdate();
                    }
                    mark.setInt(1, o.movieId());
                    mark.setString(2, today);
                    mark.executeUpdate();
                    if (done % 2000 == 0) log(String.format("%,d/%,d films done...", done, pending.size()));
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
            log(String.format("=== Done — %,d film(s) with music composers, %,d without, %,d error(s) (re-run to retry) ===",
                withComposers, pending.size() - withComposers - errors, errors));
        }
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
