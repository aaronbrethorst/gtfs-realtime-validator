package edu.usf.cutr.gtfsrtvalidator.restapi;

import java.util.List;

public class ValidationResponse {

    private final String gtfsUrl;
    private final long currentTimeMillis;
    private final List<FeedValidationResult> feeds;

    public ValidationResponse(String gtfsUrl, long currentTimeMillis, List<FeedValidationResult> feeds) {
        this.gtfsUrl = gtfsUrl;
        this.currentTimeMillis = currentTimeMillis;
        this.feeds = List.copyOf(feeds);
    }

    public String getGtfsUrl() { return gtfsUrl; }
    public long getCurrentTimeMillis() { return currentTimeMillis; }
    public List<FeedValidationResult> getFeeds() { return feeds; }
}
