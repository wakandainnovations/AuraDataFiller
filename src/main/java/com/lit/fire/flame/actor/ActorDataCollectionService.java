package com.lit.fire.flame.actor;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Background service that scans a folder of actor CSVs, expands each movie row
 * into one row per actor, and upserts into the `actors_data_collection` table.
 *
 * After importing, runs a cross-reference pass to fill in `language` from the
 * main `movies_data_collection` table wherever the actor row has it as NULL/Unknown.
 *
 * PK: (actor_name, movie_name, release_date) — deduplicates across multiple files.
 *
 * Query "how many movies has actor X acted in up to year Y":
 *   SELECT COUNT(*), array_agg(movie_name ORDER BY release_date)
 *   FROM actors_data_collection
 *   WHERE actor_name = 'X' AND release_date <= 'YYYY'
 */
public class ActorDataCollectionService implements Runnable {

    private static final String PREFIX = "[ACTOR-COLLECTOR] ";
    private static final String TABLE_NAME = "actors_data_collection";
    private static final int BATCH_SIZE = 500;

    // ---- known movie-name column aliases (case-insensitive) ----
    private static final Set<String> MOVIE_NAME_ALIASES = Set.of(
        "name", "movie name", "movie", "film", "title", "film name", "movie title"
    );

    // ---- known year/date column aliases (case-insensitive) ----
    private static final Set<String> YEAR_ALIASES = Set.of(
        "year", "release year", "release_year", "released", "release date", "release_date", "date"
    );

    // ---- known genre column aliases ----
    private static final Set<String> GENRE_ALIASES = Set.of(
        "genre", "genres", "category", "categories"
    );

    // ---- known director column aliases ----
    private static final Set<String> DIRECTOR_ALIASES = Set.of(
        "director", "directed by", "directors"
    );

    // ---- known rating column aliases ----
    private static final Set<String> RATING_ALIASES = Set.of(
        "rating", "imdb rating", "imdb_rating", "score", "imdb score", "vote_average", "rating_10"
    );

    // ---- known votes column aliases ----
    private static final Set<String> VOTES_ALIASES = Set.of(
        "votes", "vote_count", "num votes", "num_votes", "number of votes"
    );

    // ---- known runtime/duration column aliases ----
    private static final Set<String> RUNTIME_ALIASES = Set.of(
        "duration", "runtime", "runtime (min)", "duration (min)", "length", "timing_min"
    );

    // ---- known language column aliases ----
    private static final Set<String> LANGUAGE_ALIASES = Set.of(
        "language", "languages", "original_language", "lang"
    );

    // Pattern to detect actor columns: "Actor 1", "Actor 2", "Cast 1", "Lead", "Star", etc.
    private static final Pattern ACTOR_NUMBERED = Pattern.compile(
        "^(?:actor|cast|star|lead actor|supporting actor)\\s*(\\d+)?$",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public void run() {
        Properties secrets = loadProperties("secrets.properties", true);
        Properties config  = loadProperties("application.properties", false);

        if (!Boolean.parseBoolean(config.getProperty("actor.collector.enabled", "true"))) {
            log("Disabled via actor.collector.enabled=false — exiting.");
            return;
        }

        long initialDelayMs = Long.parseLong(
            config.getProperty("actor.collector.initial.delay.ms", "15000"));
        long intervalMs = Long.parseLong(
            config.getProperty("actor.collector.interval.hours", "24")) * 3_600_000L;
        String scanFolder = config.getProperty(
            "actor.collector.folder",
            "/Users/mukundv/Documents/work/space/actor_data_collection");
        String moviesTable = config.getProperty("table.name", "movies_data_collection");

        String dbUrl  = secrets.getProperty("db.url");
        String dbUser = secrets.getProperty("db.user");
        String dbPass = secrets.getProperty("db.password", "");

        // Start sacnilk actor filmography crawler in parallel (daemon so it dies with the JVM)
        Thread sacnilkCrawler = new Thread(new SacnilkActorCrawlerService(), "sacnilk-actor-crawler");
        sacnilkCrawler.setDaemon(true);
        sacnilkCrawler.start();
        log("Sacnilk actor crawler started in parallel (initial delay: "
            + config.getProperty("actor.crawler.initial.delay.ms", "30000") + " ms).");

        sleep(initialDelayMs, "initial startup delay");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                log("=== Starting actor data collection cycle ===");
                log("Scanning folder: " + scanFolder);
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
                    conn.setAutoCommit(false);
                    ensureTableExists(conn);
                    List<Path> csvFiles = listCsvFiles(scanFolder);
                    log(String.format("Found %d CSV file(s).", csvFiles.size()));
                    for (Path csv : csvFiles) {
                        log("Processing: " + csv.getFileName());
                        try {
                            processFile(conn, csv);
                        } catch (Exception e) {
                            logErr("Failed to process " + csv.getFileName() + ": " + e.getMessage());
                        }
                    }
                    enrichLanguageFromMoviesTable(conn, moviesTable);
                }
                log(String.format("=== Cycle complete. Next run in %d hour(s). ===",
                    intervalMs / 3_600_000L));
            } catch (Exception e) {
                logErr("Cycle failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            if (!sleep(intervalMs, "inter-cycle interval")) break;
        }
        log("Service stopped.");
    }

    /**
     * Prints an actor's full filmography to stdout, grouped and sorted by year.
     * Usage: --actor-filmography "Actor Name"
     * Also supports optional upper-year bound: --actor-filmography "Actor Name" 2020
     */
    public void printFilmography(String actorName, String upToYear) throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        String dbUrl  = secrets.getProperty("db.url");
        String dbUser = secrets.getProperty("db.user");
        String dbPass = secrets.getProperty("db.password", "");

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            // Check table exists
            String checkSql =
                "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema='public' AND table_name=?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, TABLE_NAME);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println("Table '" + TABLE_NAME + "' does not exist yet. Run --actor-scan first.");
                        return;
                    }
                }
            }

            String sql =
                "SELECT movie_name, release_date, language, genre, director, rating, role_position " +
                "FROM " + q(TABLE_NAME) + " " +
                "WHERE lower(trim(actor_name)) = lower(trim(?)) " +
                (upToYear != null ? "  AND left(release_date, 4) <= ? " : "") +
                "ORDER BY release_date, movie_name";

            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actorName);
                if (upToYear != null) ps.setString(2, upToYear);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{
                            rs.getString("movie_name"),
                            rs.getString("release_date"),
                            rs.getString("language"),
                            rs.getString("genre"),
                            rs.getString("director"),
                            rs.getString("rating"),
                            rs.getString("role_position")
                        });
                    }
                }
            }

            if (rows.isEmpty()) {
                System.out.printf("No filmography found for '%s'%s.%n",
                    actorName, upToYear != null ? " up to " + upToYear : "");
                return;
            }

            String header = upToYear != null
                ? String.format("Filmography of '%s' up to %s (%d film(s)):", actorName, upToYear, rows.size())
                : String.format("Filmography of '%s' (%d film(s)):", actorName, rows.size());
            System.out.println(header);
            System.out.println("=".repeat(header.length()));

            String currentYear = null;
            for (String[] r : rows) {
                String year = r[1] != null ? r[1].substring(0, Math.min(4, r[1].length())) : "Unknown";
                if (!year.equals(currentYear)) {
                    System.out.println();
                    System.out.println("  " + year);
                    System.out.println("  " + "-".repeat(4));
                    currentYear = year;
                }
                String movie    = nvl(r[0], "?");
                String lang     = nvl(r[2], "");
                String genre    = nvl(r[3], "");
                String director = nvl(r[4], "");
                String rating   = r[5] != null ? "★ " + r[5] : "";
                String pos      = r[6] != null ? " [Actor " + r[6] + "]" : "";

                StringBuilder line = new StringBuilder("    • ").append(movie).append(pos);
                if (!lang.isEmpty())     line.append("  |  ").append(lang);
                if (!genre.isEmpty())    line.append("  |  ").append(genre);
                if (!director.isEmpty()) line.append("  |  dir. ").append(director);
                if (!rating.isEmpty())   line.append("  |  ").append(rating);
                System.out.println(line);
            }
            System.out.println();
            System.out.printf("Total: %d film(s).%n", rows.size());
        }
    }

    private static String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }

    // ---- table setup ----

    private void ensureTableExists(Connection conn) throws SQLException {
        String sql =
            "CREATE TABLE IF NOT EXISTS " + q(TABLE_NAME) + " (" +
            "  \"actor_name\"         TEXT NOT NULL, " +
            "  \"movie_name\"         TEXT NOT NULL, " +
            "  \"release_date\"       TEXT NOT NULL, " +
            "  \"language\"           TEXT DEFAULT NULL, " +
            "  \"genre\"              TEXT DEFAULT NULL, " +
            "  \"director\"           TEXT DEFAULT NULL, " +
            "  \"rating\"             NUMERIC DEFAULT NULL, " +
            "  \"votes\"              NUMERIC DEFAULT NULL, " +
            "  \"runtime\"            TEXT DEFAULT NULL, " +
            "  \"role_position\"      INTEGER DEFAULT NULL, " +
            "  \"character_name\"     TEXT DEFAULT NULL, " +
            "  \"awards\"             TEXT DEFAULT NULL, " +
            "  \"streaming_platform\" TEXT DEFAULT NULL, " +
            "  \"status\"             TEXT DEFAULT NULL, " +
            "  \"sacnilk_url\"        TEXT DEFAULT NULL, " +
            "  PRIMARY KEY (\"actor_name\", \"movie_name\", \"release_date\")" +
            ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        // Ensure index for fast actor+year queries
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_actors_data_actor_date " +
                "ON " + q(TABLE_NAME) + " (\"actor_name\", \"release_date\")");
        }
        conn.commit();
    }

    // ---- CSV processing ----

    private void processFile(Connection conn, Path csvPath) throws IOException, SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> headers;

        try (Reader reader = new InputStreamReader(
                new FileInputStream(csvPath.toFile()), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .setTrim(true)
                 .setIgnoreEmptyLines(true)
                 .setAllowMissingColumnNames(true)
                 .build()
                 .parse(reader)) {

            headers = parser.getHeaderNames().stream()
                .filter(h -> h != null && !h.isBlank())
                .collect(Collectors.toList());

            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String h : headers) {
                    row.put(h, record.get(h));
                }
                rows.add(row);
            }
        }

        if (headers.isEmpty() || rows.isEmpty()) {
            log("  Skipping empty file: " + csvPath.getFileName());
            return;
        }

        HeaderMapping mapping = detectHeaders(headers);
        log(String.format("  Detected headers — movie: '%s', year: '%s', actors: %s",
            mapping.movieCol, mapping.yearCol, mapping.actorCols));

        if (mapping.movieCol == null || mapping.yearCol == null || mapping.actorCols.isEmpty()) {
            logErr("  Cannot identify required columns (movie name / year / actors) — skipping.");
            return;
        }

        upsertActorRows(conn, rows, mapping);
    }

    /**
     * Inspects CSV headers and returns a HeaderMapping describing which CSV column
     * maps to which semantic field. Handles arbitrary column naming conventions.
     */
    private HeaderMapping detectHeaders(List<String> headers) {
        String movieCol    = null;
        String yearCol     = null;
        String genreCol    = null;
        String directorCol = null;
        String ratingCol   = null;
        String votesCol    = null;
        String runtimeCol  = null;
        String languageCol = null;
        // TreeMap so Actor 1 < Actor 2 < Actor 3 (natural sort)
        Map<Integer, String> actorByPosition = new TreeMap<>();
        List<String> unpositionedActors = new ArrayList<>();

        for (String h : headers) {
            String lower = h.toLowerCase().trim();

            if (movieCol    == null && MOVIE_NAME_ALIASES.contains(lower)) { movieCol    = h; continue; }
            if (yearCol     == null && YEAR_ALIASES.contains(lower))        { yearCol     = h; continue; }
            if (genreCol    == null && GENRE_ALIASES.contains(lower))       { genreCol    = h; continue; }
            if (directorCol == null && DIRECTOR_ALIASES.contains(lower))    { directorCol = h; continue; }
            if (ratingCol   == null && RATING_ALIASES.contains(lower))      { ratingCol   = h; continue; }
            if (votesCol    == null && VOTES_ALIASES.contains(lower))       { votesCol    = h; continue; }
            if (runtimeCol  == null && RUNTIME_ALIASES.contains(lower))     { runtimeCol  = h; continue; }
            if (languageCol == null && LANGUAGE_ALIASES.contains(lower))    { languageCol = h; continue; }

            Matcher m = ACTOR_NUMBERED.matcher(lower);
            if (m.matches()) {
                String numStr = m.group(1);
                if (numStr != null) {
                    actorByPosition.put(Integer.parseInt(numStr), h);
                } else {
                    unpositionedActors.add(h);
                }
            }
        }

        // Build ordered actor column list: numbered first (1,2,3,...), then unpositioned
        List<String> actorCols = new ArrayList<>();
        actorByPosition.values().forEach(actorCols::add);
        actorCols.addAll(unpositionedActors);

        return new HeaderMapping(movieCol, yearCol, genreCol, directorCol,
            ratingCol, votesCol, runtimeCol, languageCol, actorCols);
    }

    private void upsertActorRows(Connection conn, List<Map<String, String>> rows,
                                  HeaderMapping m) throws SQLException {
        String sql =
            "INSERT INTO " + q(TABLE_NAME) + " " +
            "(\"actor_name\", \"movie_name\", \"release_date\", " +
            "\"language\", \"genre\", \"director\", \"rating\", \"votes\", " +
            "\"runtime\", \"role_position\") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (\"actor_name\", \"movie_name\", \"release_date\") DO UPDATE SET " +
            "  \"language\"     = COALESCE(EXCLUDED.\"language\",  " + q(TABLE_NAME) + ".\"language\"), " +
            "  \"genre\"        = COALESCE(EXCLUDED.\"genre\",     " + q(TABLE_NAME) + ".\"genre\"), " +
            "  \"director\"     = COALESCE(EXCLUDED.\"director\",  " + q(TABLE_NAME) + ".\"director\"), " +
            "  \"rating\"       = COALESCE(EXCLUDED.\"rating\",    " + q(TABLE_NAME) + ".\"rating\"), " +
            "  \"votes\"        = COALESCE(EXCLUDED.\"votes\",     " + q(TABLE_NAME) + ".\"votes\"), " +
            "  \"runtime\"      = COALESCE(EXCLUDED.\"runtime\",   " + q(TABLE_NAME) + ".\"runtime\"), " +
            "  \"role_position\" = COALESCE(EXCLUDED.\"role_position\", " + q(TABLE_NAME) + ".\"role_position\")";

        long inserted = 0, skipped = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batchCount = 0;

            for (Map<String, String> row : rows) {
                String movieName = blankToNull(row.get(m.movieCol));
                String rawYear   = blankToNull(row.get(m.yearCol));
                if (movieName == null || rawYear == null) { skipped++; continue; }

                String releaseDate = normalizeYear(rawYear);
                if (releaseDate == null) { skipped++; continue; }

                String genre     = blankToNull(row.get(m.genreCol));
                String director  = blankToNull(row.get(m.directorCol));
                String language  = blankToNull(row.get(m.languageCol));
                String runtimeRaw = blankToNull(row.get(m.runtimeCol));
                String runtime   = runtimeRaw == null ? null : runtimeRaw.replaceAll("(?i)\\s*min\\s*$", "").trim();
                BigDecimal rating = parseDecimal(row.get(m.ratingCol));
                BigDecimal votes  = parseDecimal(row.get(m.votesCol));

                for (int pos = 0; pos < m.actorCols.size(); pos++) {
                    String actorName = blankToNull(row.get(m.actorCols.get(pos)));
                    if (actorName == null) continue;

                    ps.setString(1, actorName);
                    ps.setString(2, movieName);
                    ps.setString(3, releaseDate);
                    ps.setString(4, language);
                    ps.setString(5, genre);
                    ps.setString(6, director);
                    if (rating != null) ps.setBigDecimal(7, rating); else ps.setNull(7, Types.NUMERIC);
                    if (votes  != null) ps.setBigDecimal(8, votes);  else ps.setNull(8, Types.NUMERIC);
                    ps.setString(9, runtime);
                    ps.setInt(10, pos + 1);

                    ps.addBatch();
                    batchCount++;
                    inserted++;

                    if (batchCount >= BATCH_SIZE) {
                        ps.executeBatch();
                        conn.commit();
                        batchCount = 0;
                        System.out.printf("  %s processed %,d actor rows...%n", PREFIX, inserted);
                    }
                }
            }
            if (batchCount > 0) {
                ps.executeBatch();
                conn.commit();
            }
        }
        log(String.format("  Upserted %,d actor rows | skipped %,d rows (missing movie or year).",
            inserted, skipped));
    }

    // ---- cross-reference language enrichment ----

    /**
     * For every actors_data_collection row where language is NULL, looks up the movie
     * in movies_data_collection (matched by movie_name + release_date year) and copies
     * the language from there if found.
     */
    private void enrichLanguageFromMoviesTable(Connection conn, String moviesTable) throws SQLException {
        // Check whether the movies table exists at all
        String checkSql =
            "SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_name = ?";
        boolean moviesExists;
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, moviesTable);
            try (ResultSet rs = ps.executeQuery()) {
                moviesExists = rs.next();
            }
        }
        if (!moviesExists) {
            log("Movies table '" + moviesTable + "' not found — skipping language enrichment.");
            return;
        }

        // Check whether movies table has a language column
        String colCheckSql =
            "SELECT 1 FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'language'";
        boolean hasLanguageCol;
        try (PreparedStatement ps = conn.prepareStatement(colCheckSql)) {
            ps.setString(1, moviesTable);
            try (ResultSet rs = ps.executeQuery()) {
                hasLanguageCol = rs.next();
            }
        }
        if (!hasLanguageCol) {
            log("Movies table has no 'language' column — skipping language enrichment.");
            return;
        }

        // Update actors rows where language IS NULL by matching movie_name + year prefix of release_date
        String updateSql =
            "UPDATE " + q(TABLE_NAME) + " AS a " +
            "SET \"language\" = m.\"language\" " +
            "FROM " + q(moviesTable) + " AS m " +
            "WHERE a.\"language\" IS NULL " +
            "  AND m.\"language\" IS NOT NULL " +
            "  AND m.\"language\" NOT IN ('Unknown', '') " +
            "  AND lower(trim(a.\"movie_name\")) = lower(trim(m.\"movie_name\")) " +
            "  AND left(a.\"release_date\", 4) = left(m.\"release_date\", 4)";
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(updateSql);
            conn.commit();
            log(String.format("Language enrichment: filled %,d actor row(s) from '%s'.", updated, moviesTable));
        }
    }

    // ---- helpers ----

    /**
     * Normalises a raw year/date value into YYYY or YYYY-MM-DD format.
     * Strips a leading '-' sign (some IMDb exports use negative years as a placeholder).
     * Strips trailing ".0" from numeric export artefacts (e.g. "2019.0").
     * Returns null if the value cannot be parsed as a plausible year.
     */
    static String normalizeYear(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "-".equals(s)) return null;

        // Strip leading dash used as placeholder (e.g. "-2019")
        if (s.startsWith("-")) s = s.substring(1);

        // Strip trailing .0 from float export artefacts
        s = s.replaceAll("\\.0+$", "");

        // If it's already YYYY-MM-DD leave as-is after stripping
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;

        // Accept bare 4-digit year
        if (s.matches("\\d{4}")) return s;

        // Try to extract 4-digit year from any mixed string (e.g. "2019 (India)")
        Matcher m = Pattern.compile("(\\d{4})").matcher(s);
        if (m.find()) return m.group(1);

        return null;
    }

    private List<Path> listCsvFiles(String folderPath) throws IOException {
        List<Path> result = new ArrayList<>();
        Path dir = Paths.get(folderPath);
        if (!Files.isDirectory(dir)) {
            logErr("Folder does not exist or is not a directory: " + folderPath);
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{csv,CSV}")) {
            for (Path p : stream) result.add(p);
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || "-".equals(t) ? null : t;
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String cleaned = s.trim().replace(",", "").replaceAll("(?i)\\s*min\\s*$", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) return null;
        try { return new BigDecimal(cleaned); } catch (NumberFormatException e) { return null; }
    }

    private static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean sleep(long ms, String reason) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted during " + reason + " — stopping.");
            return false;
        }
    }

    private void log(String msg)    { System.out.println(PREFIX + msg); }
    private void logErr(String msg) { System.err.println(PREFIX + msg); }

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

    // ---- inner types ----

    private static class HeaderMapping {
        final String movieCol, yearCol, genreCol, directorCol;
        final String ratingCol, votesCol, runtimeCol, languageCol;
        final List<String> actorCols;

        HeaderMapping(String movieCol, String yearCol, String genreCol, String directorCol,
                      String ratingCol, String votesCol, String runtimeCol, String languageCol,
                      List<String> actorCols) {
            this.movieCol    = movieCol;
            this.yearCol     = yearCol;
            this.genreCol    = genreCol;
            this.directorCol = directorCol;
            this.ratingCol   = ratingCol;
            this.votesCol    = votesCol;
            this.runtimeCol  = runtimeCol;
            this.languageCol = languageCol;
            this.actorCols   = actorCols;
        }
    }
}
