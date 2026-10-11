package com.lit.fire.flame.actor;

import com.lit.fire.flame.moviedetails.TitleMatcher;
import com.lit.fire.flame.moviedetails.TmdbMovieCredits;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Database operations for the TMDB lead-cast import (see TmdbActorImportService).
 *
 * Adds tmdb_person_id / tmdb_movie_id / gender / birthday / music_director to
 * actors_data_collection if missing, and keeps tmdb_actor_scan_movies (movies already imported)
 * so a long import can be interrupted and resumed.
 *
 * Matching an existing row to a TMDB lead credit (see {@link #saveLead}): same actor (by
 * tmdb_person_id once tagged, else case-insensitive actor_name), same movie (tmdb_movie_id once
 * tagged, else movie_name matching TMDB's title/original_title per TitleMatcher), compatible language (equal ignoring case, or the row's language is
 * NULL/blank/'Unknown'), AND release years within one of each other. When no language-compatible
 * row exists, a single same-title row from the exact same year is taken as the same film and its
 * (wrong) language corrected. The year guard matters: the table holds genuinely different
 * same-titled films for one actor (e.g. "Sawan" 1945 and 1959), which matching on
 * (actor, movie, language) alone would merge.
 *
 * Writes run in autocommit mode — each statement stands alone, so one PK collision (an update
 * whose new release_date already belongs to another row) never poisons the next write.
 */
public class TmdbActorDatabaseService implements AutoCloseable {

    private static final String TABLE = "actors_data_collection";
    private static final String MOVIES_STATUS_TABLE = "tmdb_actor_scan_movies";

    private static final String SELECT_COLS =
        "actor_name, movie_name, release_date, language, genre, director, music_director, rating, votes, " +
        "runtime, role_position, character_name, status, tmdb_person_id, tmdb_movie_id, gender, birthday";

    private final Connection connection;

    public TmdbActorDatabaseService(String url, String user, String password) throws SQLException {
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(true);
    }

    public void ensureSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + TABLE +
                " ADD COLUMN IF NOT EXISTS tmdb_person_id INTEGER," +
                " ADD COLUMN IF NOT EXISTS tmdb_movie_id INTEGER," +
                " ADD COLUMN IF NOT EXISTS gender TEXT," +
                " ADD COLUMN IF NOT EXISTS birthday TEXT," +
                " ADD COLUMN IF NOT EXISTS music_director TEXT");
            st.execute("CREATE INDEX IF NOT EXISTS idx_actors_data_lower_actor ON " + TABLE + " (lower(actor_name))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_actors_data_tmdb_person ON " + TABLE + " (tmdb_person_id)");
            st.execute("CREATE TABLE IF NOT EXISTS " + MOVIES_STATUS_TABLE + " (" +
                "tmdb_movie_id INTEGER PRIMARY KEY, last_checked DATE NOT NULL)");
        }
    }

    // ---- bookkeeping ----

    /** Movie ids among {@code ids} imported on/after {@code since} (YYYY-MM-DD). */
    public Set<Integer> moviesCheckedSince(Collection<Integer> ids, String since) throws SQLException {
        Set<Integer> done = new HashSet<>();
        if (ids.isEmpty()) return done;
        String sql = "SELECT tmdb_movie_id FROM " + MOVIES_STATUS_TABLE +
            " WHERE tmdb_movie_id = ANY (?) AND last_checked >= ?::date";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setArray(1, connection.createArrayOf("integer", ids.toArray()));
            ps.setString(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) done.add(rs.getInt(1));
            }
        }
        return done;
    }

    public void markMovie(int movieId, String today) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + MOVIES_STATUS_TABLE + " (tmdb_movie_id, last_checked) VALUES (?, ?::date) " +
                "ON CONFLICT (tmdb_movie_id) DO UPDATE SET last_checked = EXCLUDED.last_checked")) {
            ps.setInt(1, movieId);
            ps.setString(2, today);
            ps.executeUpdate();
        }
    }

    // ---- actor rows ----

    public enum SaveResult { INSERTED, UPDATED, UNCHANGED, CONFLICT }

    /** One actors_data_collection row; the PK fields of a loaded row identify it for the UPDATE. */
    private static final class Row {
        String actorName, movieName, releaseDate, language, genre, director, musicDirector, runtime,
            characterName, status, gender, birthday;
        BigDecimal rating, votes;
        Integer rolePosition, tmdbPersonId, tmdbMovieId;
    }

    /**
     * Writes {@code lead}'s credit in {@code movie}: updates the matching existing row (see class
     * javadoc) wherever any TMDB value differs from what's on file — TMDB values win; a field TMDB
     * has no value for keeps the existing one — or inserts a new row when nothing matches.
     */
    public SaveResult saveLead(TmdbMovieCredits movie, TmdbMovieCredits.Lead lead, String birthday) throws SQLException {
        Row tmdb = fromTmdb(movie, lead, birthday);
        Row existing = findMatch(loadRows(lead), movie);
        if (existing == null) {
            return insert(tmdb) ? SaveResult.INSERTED : SaveResult.CONFLICT;
        }
        Row target = merged(existing, tmdb);
        if (sameValues(existing, target)) return SaveResult.UNCHANGED;
        return update(existing, target) ? SaveResult.UPDATED : SaveResult.CONFLICT;
    }

    private List<Row> loadRows(TmdbMovieCredits.Lead lead) throws SQLException {
        // Untagged rows are matched by name; rows already tagged with a different TMDB person
        // belong to a namesake and are left alone.
        String sql = "SELECT " + SELECT_COLS + " FROM " + TABLE +
            " WHERE (lower(actor_name) = lower(?) AND (tmdb_person_id IS NULL OR tmdb_person_id = ?))" +
            " OR tmdb_person_id = ?";
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lead.name());
            ps.setInt(2, lead.personId());
            ps.setInt(3, lead.personId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row r = new Row();
                    r.actorName     = rs.getString("actor_name");
                    r.movieName     = rs.getString("movie_name");
                    r.releaseDate   = rs.getString("release_date");
                    r.language      = rs.getString("language");
                    r.genre         = rs.getString("genre");
                    r.director      = rs.getString("director");
                    r.musicDirector = rs.getString("music_director");
                    r.rating        = rs.getBigDecimal("rating");
                    r.votes         = rs.getBigDecimal("votes");
                    r.runtime       = rs.getString("runtime");
                    r.rolePosition  = (Integer) rs.getObject("role_position");
                    r.characterName = rs.getString("character_name");
                    r.status        = rs.getString("status");
                    r.tmdbPersonId  = (Integer) rs.getObject("tmdb_person_id");
                    r.tmdbMovieId   = (Integer) rs.getObject("tmdb_movie_id");
                    r.gender        = rs.getString("gender");
                    r.birthday      = rs.getString("birthday");
                    rows.add(r);
                }
            }
        }
        return rows;
    }

    private static Row findMatch(List<Row> pool, TmdbMovieCredits movie) {
        for (Row r : pool) {
            if (r.tmdbMovieId != null && r.tmdbMovieId == movie.movieId()) return r;
        }

        Integer movieYear = year(movie.releaseDate());

        Row best = null;
        int bestRank = Integer.MAX_VALUE;
        // Rows with the right title and exact year but a disagreeing language — used only when
        // nothing language-compatible exists (the row's language is wrong, e.g. a Tamil film
        // stored as Hindi). Safe because the table never holds one film in two languages for
        // the same actor (verified 2026-10-11: zero such rows).
        List<Row> languageMismatches = new ArrayList<>();
        for (Row r : pool) {
            if (r.tmdbMovieId != null) continue;  // already tied to a different TMDB movie
            if (!TitleMatcher.matches(r.movieName, movie.title())
                && !TitleMatcher.matches(r.movieName, movie.originalTitle())) continue;

            Integer rowYear = year(r.releaseDate);
            int yearDiff = 0;
            if (movieYear != null && rowYear != null) {
                yearDiff = Math.abs(movieYear - rowYear);
                if (yearDiff > 1) continue;
            }

            boolean languageExact = movie.language() != null && movie.language().equalsIgnoreCase(r.language);
            if (!languageExact && !isUnknownLanguage(r.language)) {
                if (yearDiff == 0) languageMismatches.add(r);
                continue;
            }

            int rank = yearDiff * 2 + (languageExact ? 0 : 1);
            if (rank < bestRank) {
                bestRank = rank;
                best = r;
            }
        }
        if (best == null && languageMismatches.size() == 1) return languageMismatches.get(0);
        return best;
    }

    private static boolean isUnknownLanguage(String language) {
        return language == null || language.isBlank() || "unknown".equalsIgnoreCase(language.trim());
    }

    private static Row fromTmdb(TmdbMovieCredits movie, TmdbMovieCredits.Lead lead, String birthday) {
        Row t = new Row();
        t.actorName     = lead.name();
        t.movieName     = movie.title();
        t.releaseDate   = movie.releaseDate();
        t.language      = movie.language();
        t.genre         = movie.genre();
        t.director      = movie.directors();
        t.musicDirector = movie.musicDirectors();
        t.rating        = movie.rating() != null ? BigDecimal.valueOf(movie.rating()) : null;
        t.votes         = movie.votes() != null ? BigDecimal.valueOf(movie.votes()) : null;
        t.runtime       = movie.runtimeMinutes() != null ? String.valueOf(movie.runtimeMinutes()) : null;
        t.rolePosition  = lead.rolePosition();
        t.characterName = lead.character();
        t.status        = movie.status();
        t.tmdbPersonId  = lead.personId();
        t.tmdbMovieId   = movie.movieId();
        t.gender        = lead.gender();
        t.birthday      = birthday;
        return t;
    }

    /** {@code existing}'s identity (actor_name, movie_name) with every non-null TMDB value applied. */
    private static Row merged(Row existing, Row tmdb) {
        Row t = new Row();
        t.actorName     = existing.actorName;
        t.movieName     = existing.movieName;
        t.releaseDate   = tmdb.releaseDate != null ? tmdb.releaseDate : existing.releaseDate;
        t.language      = tmdb.language != null ? tmdb.language : existing.language;
        t.genre         = tmdb.genre != null ? tmdb.genre : existing.genre;
        t.director      = tmdb.director != null ? tmdb.director : existing.director;
        t.musicDirector = tmdb.musicDirector != null ? tmdb.musicDirector : existing.musicDirector;
        t.rating        = tmdb.rating != null ? tmdb.rating : existing.rating;
        t.votes         = tmdb.votes != null ? tmdb.votes : existing.votes;
        t.runtime       = tmdb.runtime != null ? tmdb.runtime : existing.runtime;
        t.rolePosition  = tmdb.rolePosition != null ? tmdb.rolePosition : existing.rolePosition;
        t.characterName = tmdb.characterName != null ? tmdb.characterName : existing.characterName;
        t.status        = tmdb.status != null ? tmdb.status : existing.status;
        t.tmdbPersonId  = tmdb.tmdbPersonId;
        t.tmdbMovieId   = tmdb.tmdbMovieId;
        t.gender        = tmdb.gender != null ? tmdb.gender : existing.gender;
        t.birthday      = tmdb.birthday != null ? tmdb.birthday : existing.birthday;
        return t;
    }

    private static boolean sameValues(Row a, Row b) {
        return Objects.equals(a.releaseDate, b.releaseDate)
            && Objects.equals(a.language, b.language)
            && Objects.equals(a.genre, b.genre)
            && Objects.equals(a.director, b.director)
            && Objects.equals(a.musicDirector, b.musicDirector)
            && numEquals(a.rating, b.rating)
            && numEquals(a.votes, b.votes)
            && Objects.equals(a.runtime, b.runtime)
            && Objects.equals(a.rolePosition, b.rolePosition)
            && Objects.equals(a.characterName, b.characterName)
            && Objects.equals(a.status, b.status)
            && Objects.equals(a.tmdbPersonId, b.tmdbPersonId)
            && Objects.equals(a.tmdbMovieId, b.tmdbMovieId)
            && Objects.equals(a.gender, b.gender)
            && Objects.equals(a.birthday, b.birthday);
    }

    private static boolean numEquals(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /**
     * Returns false when the new release_date collides with another row's primary key; in that
     * case every other field is still updated, keeping the row's current release_date.
     */
    private boolean update(Row existing, Row t) throws SQLException {
        try {
            executeUpdate(existing, t, t.releaseDate);
            return true;
        } catch (SQLException e) {
            if (!"23505".equals(e.getSQLState())) throw e;
            executeUpdate(existing, t, existing.releaseDate);
            return false;
        }
    }

    private void executeUpdate(Row existing, Row t, String releaseDate) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET release_date = ?, language = ?, genre = ?, director = ?, " +
            "music_director = ?, rating = ?, votes = ?, runtime = ?, role_position = ?, character_name = ?, " +
            "status = ?, tmdb_person_id = ?, tmdb_movie_id = ?, gender = ?, birthday = ? " +
            "WHERE actor_name = ? AND movie_name = ? AND release_date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, releaseDate);
            int i = bindValues(ps, 2, t);
            ps.setString(i++, existing.actorName);
            ps.setString(i++, existing.movieName);
            ps.setString(i, existing.releaseDate);
            ps.executeUpdate();
        }
    }

    /** Returns false when a row with this (actor_name, movie_name, release_date) already exists but didn't match (language disagrees). */
    private boolean insert(Row t) throws SQLException {
        String sql = "INSERT INTO " + TABLE + " (actor_name, movie_name, release_date, language, genre, director, " +
            "music_director, rating, votes, runtime, role_position, character_name, status, tmdb_person_id, " +
            "tmdb_movie_id, gender, birthday) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, t.actorName);
            ps.setString(2, t.movieName);
            ps.setString(3, t.releaseDate);
            bindValues(ps, 4, t);
            return ps.executeUpdate() == 1;
        }
    }

    private static int bindValues(PreparedStatement ps, int i, Row t) throws SQLException {
        ps.setString(i++, t.language);
        ps.setString(i++, t.genre);
        ps.setString(i++, t.director);
        ps.setString(i++, t.musicDirector);
        ps.setBigDecimal(i++, t.rating);
        ps.setBigDecimal(i++, t.votes);
        ps.setString(i++, t.runtime);
        setInt(ps, i++, t.rolePosition);
        ps.setString(i++, t.characterName);
        ps.setString(i++, t.status);
        setInt(ps, i++, t.tmdbPersonId);
        setInt(ps, i++, t.tmdbMovieId);
        ps.setString(i++, t.gender);
        ps.setString(i++, t.birthday);
        return i;
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER);
        else ps.setInt(i, v);
    }

    private static Integer year(String date) {
        if (date == null || date.length() < 4) return null;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
