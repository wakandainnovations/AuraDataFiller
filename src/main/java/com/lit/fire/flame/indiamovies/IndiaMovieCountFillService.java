package com.lit.fire.flame.indiamovies;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * One-shot fill (--india-movie-count-fill-once) of india_movies_data_collection's
 * lead_actor_1..5_movie_count, director_movie_count and music_director_movie_count: how many films
 * the person in lead_actor_1..5 / director / music_director had already released before this
 * movie's release date. Recomputes every row (a few seconds, all in SQL), so it is safe to re-run
 * after either table changes.
 *
 * A person's films come from actors_data_collection (actor_name, director, music_director) plus
 * the India table's own lead_actor_1..5 / director / music_director. Rules:
 * <ul>
 *   <li>Films are identified by normalised title (lower-case, alphanumerics only) + year, taking
 *       the earliest full date among its credits (a year-only credit of a dated film is the same
 *       film), and dated films collapse to one per release date, so alternate titles of one
 *       release ("Lagaan" / "Lagaan: Once Upon a Time in India") count once.</li>
 *   <li>Dated credits count when released strictly before; year-only credits (no month/day) only
 *       when their year is before the release year, since their order within it is unknown.</li>
 *   <li>Leads are matched by TMDB person id, not name, because one name in actors_data_collection
 *       can cover several people (Murali, Krishna) and some names were merged wrongly
 *       ("N.T. Rama Rao" holds both NTR Sr. and Jr.). The id comes from the same film's actor row
 *       with the same name, else the same billing slot in a film released within 3 days under a
 *       title starting alike, else a name only one person id has. A lead with an id counts only
 *       its TMDB-sourced rows (tmdb_movie_id set; the full filmography is imported by
 *       --filmography-tmdb-import-once), since older id-less rows hold junk ("Amitabh Bachchan
 *       Movies 1982") and alternate titles of TMDB films; a lead without one counts every row
 *       under its name. "Himself"/"Herself" appearances are not counted.</li>
 *   <li>Directors and music directors are matched by normalised name (no ids exist for them);
 *       comma-separated co-directors count films of any of them. A music director's films are
 *       those naming them in music_director or music_composers (every TMDB music credit, so a duo
 *       member credited second still counts), and music teams the India table names as a duo
 *       ("Vishal–Shekhar") also count the members TMDB credits individually.</li>
 * </ul>
 * A count is NULL when its name column is empty, else at least 0.
 */
public class IndiaMovieCountFillService {

    private static final String PREFIX = "[INDIA-MOVIE-COUNT] ";
    private static final String TABLE = "india_movies_data_collection";
    private static final String ACTORS_TABLE = "actors_data_collection";
    private static final List<String> SLOTS = List.of(
        "lead_actor_1", "lead_actor_2", "lead_actor_3", "lead_actor_4", "lead_actor_5", "director", "music_director");

    /** Music teams the India table credits by team name, with the individual names TMDB credits instead. */
    private static final String[][] TEAM_MEMBERS = {
        {"Vishal–Shekhar", "Vishal Dadlani"}, {"Vishal–Shekhar", "Shekhar Ravjiani"},
        {"Salim-Sulaiman", "Salim Merchant"}, {"Salim-Sulaiman", "Sulaiman Merchant"},
        {"Sajid-Wajid", "Sajid Ali Khan"}, {"Sajid-Wajid", "Wajid Khan"},
        {"Ajay-Atul", "Ajay Gogavale"}, {"Ajay-Atul", "Atul Gogavale"},
        {"Jatin–Lalit", "Jatin Pandit"}, {"Jatin–Lalit", "Lalit Pandit"},
        {"Nadeem-Shravan", "Nadeem Akhtar Saifi"}, {"Nadeem-Shravan", "Nadeem Saifi"}, {"Nadeem-Shravan", "Shravan Kumar Rathod"},
        {"Sachin–Jigar", "Sachin Sanghvi"}, {"Sachin–Jigar", "Jigar Saraiya"},
        {"Bishakh-Kanish", "Bishakh Jyoti Majumdar"},
    };

    public void runOnce() throws Exception {
        Properties secrets = loadProperties("secrets.properties", true);
        try (Connection conn = DriverManager.getConnection(secrets.getProperty("db.url"),
                secrets.getProperty("db.user"), secrets.getProperty("db.password", ""))) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String slot : SLOTS) {
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS " + slot + "_movie_count INTEGER");
                }
                buildCounts(st);
                int updated = st.executeUpdate(updateSql());
                conn.commit();
                log(String.format("Updated %,d row(s).", updated));
                logSummary(st);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** Builds temp table mc_count(rk, slot, n), rk being the India row's primary key as one string. */
    private static void buildCounts(Statement st) throws SQLException {
        st.execute("CREATE TEMP TABLE mc_team_member(team TEXT, member TEXT) ON COMMIT DROP");
        StringBuilder values = new StringBuilder();
        for (String[] tm : TEAM_MEMBERS) {
            values.append(values.length() == 0 ? "" : ",").append("('").append(tm[0]).append("','").append(tm[1]).append("')");
        }
        st.execute("INSERT INTO mc_team_member VALUES " + values);

        st.execute("""
            CREATE TEMP TABLE mc_actor_row ON COMMIT DROP AS
            SELECT tmdb_person_id AS pid, tmdb_movie_id AS mid, role_position AS pos, %s AS nkey, %s AS fkey,
                   release_date AS rdate
            FROM %s WHERE character_name IS NULL OR character_name !~* '\\m(him|her|them)self\\M|^self\\M'""".formatted(norm("actor_name"), filmKey("movie_name", "release_date"), ACTORS_TABLE));
        st.execute("CREATE INDEX ON mc_actor_row(fkey)");
        st.execute("""
            CREATE TEMP TABLE mc_name_ids ON COMMIT DROP AS
            SELECT nkey, count(DISTINCT pid) AS n_ids, min(pid) AS only_pid FROM mc_actor_row GROUP BY nkey""");
        st.execute("CREATE INDEX ON mc_name_ids(nkey)");

        st.execute("""
            CREATE TEMP TABLE mc_movie ON COMMIT DROP AS
            SELECT concat_ws(chr(31), movie_name, release_date, language) AS rk,
                   to_char(release_date::date, 'YYYY-MM-DD') AS rdate, %s AS fkey,
                   lead_actor_1, lead_actor_2, lead_actor_3, lead_actor_4, lead_actor_5, director, music_director
            FROM %s WHERE release_date ~ '^\\d{4}-\\d{1,2}-\\d{1,2}$'""".formatted(filmKey("movie_name", "release_date"), TABLE));

        st.execute("""
            CREATE TEMP TABLE mc_lead ON COMMIT DROP AS
            SELECT m.rk, m.rdate, m.fkey, l.pos::int AS pos, 'lead_actor_' || l.pos AS slot, %s AS nkey, NULL::int AS pid
            FROM mc_movie m,
                 unnest(array[lead_actor_1, lead_actor_2, lead_actor_3, lead_actor_4, lead_actor_5]) WITH ORDINALITY l(name, pos)
            WHERE %s <> ''""".formatted(norm("l.name"), norm("l.name")));
        // TMDB person id: same film + same name, else same billing slot in a film released within 3 days
        // under a title starting alike (TMDB and the India table can differ by a day, "RRR (Rise Roar Revolt)"),
        // else a name only one person id has
        st.execute("""
            UPDATE mc_lead l SET pid = coalesce(
              (SELECT min(a.pid) FROM mc_actor_row a WHERE a.fkey = l.fkey AND a.nkey = l.nkey AND a.pid IS NOT NULL),
              (SELECT min(a.pid) FROM mc_actor_row a WHERE a.pos = l.pos AND a.pid IS NOT NULL AND length(a.rdate) = 10
                 AND abs(a.rdate::date - l.rdate::date) <= 3 AND left(a.fkey, 3) = left(l.fkey, 3)),
              (SELECT n.only_pid FROM mc_name_ids n WHERE n.nkey = l.nkey AND n.n_ids = 1))""");

        st.execute("""
            CREATE TEMP TABLE mc_actor_credit ON COMMIT DROP AS
            SELECT pid, mid, nkey, fkey, rdate FROM mc_actor_row WHERE nkey <> ''
            UNION ALL SELECT pid, NULL, nkey, fkey, rdate FROM mc_lead""");
        st.execute("CREATE INDEX ON mc_actor_credit(pid)");
        st.execute("CREATE INDEX ON mc_actor_credit(nkey)");

        st.execute("""
            CREATE TEMP TABLE mc_crew_credit ON COMMIT DROP AS
            WITH raw(role, name, title, rdate) AS (
              SELECT 'director', d, movie_name, release_date FROM %1$s, unnest(string_to_array(director, ',')) d
              UNION ALL SELECT 'music', music_director, movie_name, release_date FROM %1$s
              UNION ALL SELECT 'music', c, movie_name, release_date FROM %1$s, unnest(string_to_array(music_composers, ',')) c
              UNION ALL SELECT 'director', d, movie_name, release_date FROM %2$s, unnest(string_to_array(director, ',')) d
              UNION ALL SELECT 'music', music_director, movie_name, release_date FROM %2$s
            )
            SELECT role, %3$s AS nkey, %4$s AS fkey, rdate FROM raw WHERE %3$s <> ''"""
            .formatted(ACTORS_TABLE, TABLE, norm("name"), filmKey("title", "rdate")));
        st.execute("CREATE INDEX ON mc_crew_credit(role, nkey)");

        st.execute("""
            CREATE TEMP TABLE mc_crew ON COMMIT DROP AS
            WITH s(rk, rdate, fkey, slot, role, name) AS (
              SELECT rk, rdate, fkey, 'director', 'director', d FROM mc_movie, unnest(string_to_array(director, ',')) d
              UNION ALL SELECT rk, rdate, fkey, 'music_director', 'music', music_director FROM mc_movie
              UNION ALL SELECT rk, rdate, fkey, 'music_director', 'music', t.member
                FROM mc_movie m JOIN mc_team_member t ON t.team = m.music_director
            )
            SELECT rk, rdate, fkey, slot, role, %1$s AS nkey FROM s WHERE %1$s <> ''""".formatted(norm("name")));

        // (slot, credited film) pairs: a lead's films are its person's TMDB-sourced rows (its full TMDB
        // filmography is imported, see TmdbFilmographyImportService), or every credit under its name when no
        // person id was found; crew by name
        st.execute("""
            CREATE TEMP TABLE mc_pair ON COMMIT DROP AS
            SELECT l.rk, l.slot, l.rdate AS t_rdate, l.fkey AS t_fkey, c.fkey, c.rdate
            FROM mc_lead l JOIN mc_actor_credit c ON c.pid = l.pid AND c.mid IS NOT NULL
            UNION ALL
            SELECT l.rk, l.slot, l.rdate, l.fkey, c.fkey, c.rdate
            FROM mc_lead l JOIN mc_actor_credit c ON c.nkey = l.nkey
            WHERE l.pid IS NULL
            UNION ALL
            SELECT t.rk, t.slot, t.rdate, t.fkey, c.fkey, c.rdate
            FROM mc_crew t JOIN mc_crew_credit c ON c.role = t.role AND c.nkey = t.nkey""");

        // one row per credited film (title + year), dated if any of its credits is, then one film per date
        st.execute("""
            CREATE TEMP TABLE mc_count ON COMMIT DROP AS
            WITH film AS (
              SELECT rk, slot, t_rdate, t_fkey, fkey,
                     coalesce(to_char(min(rdate::date) FILTER (WHERE length(rdate) > 4), 'YYYY-MM-DD'), min(rdate)) AS rdate
              FROM mc_pair WHERE rdate ~ '^\\d{4}(-\\d{1,2}-\\d{1,2})?$'
              GROUP BY rk, slot, t_rdate, t_fkey, fkey
            )
            SELECT rk, slot,
                   count(DISTINCT CASE WHEN length(rdate) = 4 THEN fkey ELSE rdate END) FILTER (
                     WHERE fkey <> t_fkey AND (
                       (length(rdate) = 4 AND rdate < left(t_rdate, 4)) OR
                       (length(rdate) > 4 AND rdate < t_rdate))) AS n
            FROM film GROUP BY rk, slot""");
    }

    private static String updateSql() {
        StringBuilder set = new StringBuilder(), pivot = new StringBuilder();
        for (String slot : SLOTS) {
            set.append(set.length() == 0 ? "" : ", ").append(String.format(
                "%1$s_movie_count = CASE WHEN COALESCE(TRIM(i.%1$s), '') = '' THEN NULL ELSE COALESCE(p.%1$s, 0) END", slot));
            pivot.append(String.format(", max(n) FILTER (WHERE slot = '%1$s') AS %1$s", slot));
        }
        return "UPDATE " + TABLE + " i SET " + set +
            " FROM (SELECT m.rk" + pivot + " FROM mc_movie m LEFT JOIN mc_count c ON c.rk = m.rk GROUP BY m.rk) p" +
            " WHERE p.rk = concat_ws(chr(31), i.movie_name, i.release_date, i.language)";
    }

    private void logSummary(Statement st) throws SQLException {
        for (String slot : SLOTS) {
            String col = slot + "_movie_count";
            try (ResultSet rs = st.executeQuery("SELECT count(" + col + "), round(avg(" + col + "), 1), max(" + col + ")" +
                    ", count(*) FILTER (WHERE " + col + " = 0) FROM " + TABLE)) {
                rs.next();
                log(String.format("%-26s filled %,5d  avg %6s  max %4d  zero %,d",
                    col, rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)));
            }
        }
    }

    /** Lower-case alphanumerics only, so "A.R. Rahman" = "A. R. Rahman" and "Dhoom 3" = "Dhoom 3.". */
    private static String norm(String expr) {
        return "regexp_replace(lower(" + expr + "), '[^[:alnum:]]', '', 'g')";
    }

    private static String filmKey(String titleExpr, String dateExpr) {
        return norm(titleExpr) + " || '|' || left(" + dateExpr + ", 4)";
    }

    private void log(String msg) {
        System.out.println(PREFIX + msg);
        System.out.flush();
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
