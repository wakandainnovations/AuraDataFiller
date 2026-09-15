package com.lit.fire.flame.moviedetails;

/**
 * One TMDB-sourced set of movie facts (see TmdbClient), ready to write into
 * movies_data_collection via MovieDetailsDatabaseService#updateDetailsIfMissing. Every field may
 * be null when TMDB simply doesn't carry that particular piece of data for the matched movie
 * (e.g. no votes yet, no India release/certification on file) — a null is written as "still
 * unknown", never as a guessed value, since every value here traces back to a real TMDB record.
 */
public record MovieDetails(Double rating, Integer runtimeMinutes, String genre, String country,
                            String productionCompanies, String directors, String cbfcRating) {

    public boolean isEmpty() {
        return rating == null && runtimeMinutes == null && genre == null && country == null
            && productionCompanies == null && directors == null && cbfcRating == null;
    }
}
