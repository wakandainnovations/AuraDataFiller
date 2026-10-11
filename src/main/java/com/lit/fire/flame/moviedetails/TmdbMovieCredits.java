package com.lit.fire.flame.moviedetails;

import java.util.List;

/**
 * One TMDB movie's details plus its top-billed cast and key crew (see
 * TmdbClient#fetchMovieCredits).
 *
 * @param releaseDate YYYY-MM-DD
 * @param language    original_language expanded to a name ("Hindi"), or null when TMDB has none
 * @param directors      comma-separated, or null
 * @param musicDirectors one name (or the team "Shankar Ehsan Loy"), or null
 * @param musicComposers  every name credited under a music-director job, comma-separated, or null
 */
public record TmdbMovieCredits(int movieId, String title, String originalTitle, String releaseDate,
                               String language, String genre, Double rating, Integer votes,
                               Integer runtimeMinutes, String status, String directors,
                               String musicDirectors, String musicComposers, List<Lead> leads) {

    /**
     * One top-billed cast member.
     *
     * @param gender       "Female" / "Male" / "Non-binary", or null when TMDB has it unset
     * @param rolePosition 1-based billing position
     */
    public record Lead(int personId, String name, String gender, int rolePosition, String character) {}
}
