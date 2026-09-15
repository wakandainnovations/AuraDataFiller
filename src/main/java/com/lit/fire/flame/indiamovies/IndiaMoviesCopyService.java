package com.lit.fire.flame.indiamovies;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * One-shot: copies every Indian-language row already sitting in movies_data_collection (or
 * table.name) — filled by this app's other backfill services (legacy CSV imports, Sacnilk/
 * Koimoi/BoxOfficeMojo crawlers, Tamil/Kannada CSV imports, etc.) — into
 * india_movies_data_collection, for every movie whose movie_name isn't already present there
 * (see IndiaMoviesImportService, the TMDB catalog scan that originally populated it) and whose
 * budget AND revenue are both non-zero.
 *
 * Unlike IndiaMoviesImportService (which discovers movies fresh from TMDB's own catalog and
 * only ever writes the TMDB-shaped column subset), this is a straight full-row SQL copy — every
 * column movies_data_collection already has filled for that row comes along, since the target
 * table is an exact schema mirror of the source (see IndiaMoviesDatabaseService). No network
 * calls, no TMDB rate limit involved.
 *
 * Intentionally a manual, one-shot command (--india-movies-copy-once): a single SQL statement
 * either copies the current gap or it doesn't; there's no daemon-style backlog to keep working
 * through like this app's other repeating services. Safe to re-run — already-copied movie_names
 * are skipped every time.
 */
public class IndiaMoviesCopyService {

    private static final String PREFIX = "[INDIA-MOVIES-COPY] ";

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        String dbUrl      = secrets.getProperty("db.url");
        String dbUser     = secrets.getProperty("db.user");
        String dbPassword = secrets.getProperty("db.password", "");
        String sourceTable = config.getProperty("table.name", "movies_data_collection");
        String targetTable = config.getProperty("indiamovies.target.table", "india_movies_data_collection");

        try (IndiaMoviesDatabaseService db = new IndiaMoviesDatabaseService(dbUrl, dbUser, dbPassword, sourceTable, targetTable)) {
            if (!db.tableExists(sourceTable)) {
                logErr("Source table '" + sourceTable + "' does not exist — aborting.");
                return;
            }
            if (!db.tableExists(targetTable)) {
                logErr("Target table '" + targetTable + "' does not exist yet — run --india-movies-import-once first.");
                return;
            }

            log(String.format(
                "=== Copying existing Indian-language movies (budget+revenue non-zero, movie_name not already in '%s') from '%s' ===",
                targetTable, sourceTable));
            int copied = db.copyExistingIndianLanguageMovies();
            log(String.format("=== Copy complete — %,d row(s) copied into '%s' ===", copied, targetTable));
        }
    }

    // ---- helpers ----

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
