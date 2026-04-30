package edu.usf.cutr.gtfsrtvalidator.restapi;

import java.util.List;

public class FeedValidationResult {

    private final String gtfsRtUrl;
    private final long feedTimestampSeconds;
    private final List<ValidationError> results;
    private final List<SkippedRule> skippedRules;

    public FeedValidationResult(String gtfsRtUrl, long feedTimestampSeconds,
                                List<ValidationError> results, List<SkippedRule> skippedRules) {
        this.gtfsRtUrl = gtfsRtUrl;
        this.feedTimestampSeconds = feedTimestampSeconds;
        this.results = List.copyOf(results);
        this.skippedRules = List.copyOf(skippedRules);
    }

    public String getGtfsRtUrl() { return gtfsRtUrl; }
    public long getFeedTimestampSeconds() { return feedTimestampSeconds; }
    public List<ValidationError> getResults() { return results; }
    public List<SkippedRule> getSkippedRules() { return skippedRules; }
}
