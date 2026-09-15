package com.lit.fire.flame.moviedetails;

/**
 * One full TMDB-sourced record for an India-origin movie discovered via {@link TmdbClient}'s
 * catalog scan (see {@code com.lit.fire.flame.indiamovies.IndiaMoviesImportService}) — unlike
 * {@link MovieDetails}, which only carries the fields written back onto an existing
 * movies_data_collection row, this also carries the primary-key fields (movieName, releaseDate,
 * language) and budget/revenue, since this record is used to insert or update a brand-new row
 * in india_movies_data_collection rather than fill gaps in one that already exists.
 */
public record IndiaMovieRecord(String movieName, String releaseDate, String language,
                                Double rating, Integer runtimeMinutes, String genre, String country,
                                Long budget, Long revenue, String productionCompanies,
                                String directors, String cbfcRating) {
}
