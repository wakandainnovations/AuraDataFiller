package com.lit.fire.flame.youtube;

import java.time.LocalDate;

/** Aggregated YouTube promo-metrics collected for one movie in a single cycle. */
class YoutubeRecord {

    LocalDate trailerDate;
    LocalDate teaserDate;
    LocalDate songDate;

    Integer trailerDaysToRelease;
    Integer teaserDaysToRelease;
    Integer songDaysToRelease;

    Long trailerViews;
    Long teaserViews;
    Long songViews;

    Long trailerComments;
    Long teaserComments;
    Long songComments;
}
