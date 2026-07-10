package com.lit.fire.flame.youtube;

import java.time.OffsetDateTime;

/** One candidate video returned by search.list, later enriched with statistics. */
class YoutubeVideoMatch {

    final String videoId;
    final String title;
    String channelId;
    OffsetDateTime publishedAt;
    Long viewCount;
    Long commentCount;

    YoutubeVideoMatch(String videoId, String title) {
        this.videoId = videoId;
        this.title = title;
    }
}
