package com.lit.fire.flame.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin REST client for the YouTube Data API v3 (search.list + videos.list).
 * Uses java.net.http.HttpClient + Jackson, matching this codebase's existing
 * HTTP conventions elsewhere (no Google client library dependency added).
 */
public class YoutubeApiClient {

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private static final String VIDEOS_URL = "https://www.googleapis.com/youtube/v3/videos";

    /** Quota cost of one search.list call, per Google's published pricing. */
    public static final int SEARCH_COST_UNITS = 100;
    /** Quota cost of one videos.list call, regardless of how many ids are batched in. */
    public static final int VIDEOS_COST_UNITS = 1;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    public YoutubeApiClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Searches for videos matching {@code query}, restricted to the window
     * [publishedAfter, publishedBefore]. Returns up to maxResults candidates,
     * ordered by YouTube's relevance ranking.
     */
    public List<YoutubeVideoMatch> search(String query, LocalDate publishedAfter,
                                           LocalDate publishedBefore, int maxResults)
            throws IOException, InterruptedException {
        String url = SEARCH_URL +
            "?part=snippet" +
            "&type=video" +
            "&order=relevance" +
            "&maxResults=" + maxResults +
            "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
            "&publishedAfter=" + toRfc3339(publishedAfter.atStartOfDay(ZoneOffset.UTC)) +
            "&publishedBefore=" + toRfc3339(publishedBefore.plusDays(1).atStartOfDay(ZoneOffset.UTC)) +
            "&key=" + apiKey;

        JsonNode root = get(url);
        List<YoutubeVideoMatch> results = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String videoId = item.path("id").path("videoId").asText(null);
            if (videoId == null) continue;
            JsonNode snippet = item.path("snippet");
            YoutubeVideoMatch match = new YoutubeVideoMatch(videoId, snippet.path("title").asText(""));
            match.channelId = snippet.path("channelId").asText(null);
            String publishedAt = snippet.path("publishedAt").asText(null);
            if (publishedAt != null) {
                match.publishedAt = OffsetDateTime.parse(publishedAt);
            }
            results.add(match);
        }
        return results;
    }

    /**
     * Fills viewCount/commentCount for the given matches via a single batched
     * videos.list call — comma-joined ids cost 1 quota unit total, regardless of count.
     * Videos with comments disabled simply have no commentCount in the response,
     * left null here.
     */
    public void fillStatistics(List<YoutubeVideoMatch> matches) throws IOException, InterruptedException {
        if (matches.isEmpty()) return;
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) ids.append(',');
            ids.append(matches.get(i).videoId);
        }
        String url = VIDEOS_URL + "?part=statistics&id=" +
            URLEncoder.encode(ids.toString(), StandardCharsets.UTF_8) + "&key=" + apiKey;

        JsonNode root = get(url);
        for (JsonNode item : root.path("items")) {
            String id = item.path("id").asText(null);
            if (id == null) continue;
            JsonNode stats = item.path("statistics");
            Long viewCount = stats.has("viewCount") ? stats.path("viewCount").asLong() : null;
            Long commentCount = stats.has("commentCount") ? stats.path("commentCount").asLong() : null;
            // Update every match sharing this id (defense in depth — normally the caller
            // already de-duplicates video ids across categories before calling this).
            for (YoutubeVideoMatch match : matches) {
                if (match.videoId.equals(id)) {
                    match.viewCount = viewCount;
                    match.commentCount = commentCount;
                }
            }
        }
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("YouTube API HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private String toRfc3339(java.time.ZonedDateTime dateTime) {
        return URLEncoder.encode(dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), StandardCharsets.UTF_8);
    }
}
