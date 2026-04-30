package edu.usf.cutr.gtfsrtvalidator.restapi;

import com.google.transit.realtime.GtfsRealtime;
import edu.usf.cutr.gtfsrtvalidator.lib.model.helper.ErrorListHelperModel;
import edu.usf.cutr.gtfsrtvalidator.lib.util.GtfsUtils;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.GtfsMetadata;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.interfaces.FeedEntityValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.CrossFeedDescriptorValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.FrequencyTypeOneValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.FrequencyTypeZeroValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.HeaderValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.StopTimeUpdateValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.StopValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.TimestampValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.TripDescriptorValidator;
import edu.usf.cutr.gtfsrtvalidator.lib.validation.rules.VehicleValidator;
import io.javalin.http.HttpStatus;
import org.onebusaway.gtfs.impl.GtfsDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_PARALLEL_FETCHES = 8;

    private final List<FeedEntityValidator> validators;

    public ValidationService() {
        this.validators = List.of(
                new CrossFeedDescriptorValidator(),
                new VehicleValidator(),
                new TimestampValidator(),
                new StopTimeUpdateValidator(),
                new TripDescriptorValidator(),
                new StopValidator(),
                new FrequencyTypeZeroValidator(),
                new FrequencyTypeOneValidator(),
                new HeaderValidator()
        );
    }

    public ValidationResponse validate(String gtfsUrl, List<String> gtfsRtUrls) throws ValidationException {
        requireHttpUrl(gtfsUrl, "gtfsUrl");
        if (gtfsRtUrls == null || gtfsRtUrls.isEmpty()) {
            throw new ValidationException(HttpStatus.BAD_REQUEST, "gtfsRtUrls must contain at least one URL");
        }
        for (int i = 0; i < gtfsRtUrls.size(); i++) {
            requireHttpUrl(gtfsRtUrls.get(i), "gtfsRtUrls[" + i + "]");
        }

        Path gtfsTempFile = null;
        try {
            gtfsTempFile = downloadToTempFile(gtfsUrl);

            GtfsDaoImpl gtfsData = new GtfsDaoImpl();
            GtfsReader reader = new GtfsReader();
            try {
                reader.setInputLocation(gtfsTempFile.toFile());
                reader.setEntityStore(gtfsData);
                reader.run();
            } catch (IOException | RuntimeException e) {
                throw new ValidationException(HttpStatus.UNPROCESSABLE_CONTENT, "Failed to parse GTFS zip: " + e.getMessage(), e);
            }

            String agencyTimezone = firstAgencyTimezone(gtfsData.getAllAgencies());
            if (agencyTimezone == null) {
                throw new ValidationException(HttpStatus.UNPROCESSABLE_CONTENT, "GTFS feed has no agencies with a timezone");
            }

            GtfsMetadata gtfsMetadata = new GtfsMetadata(
                    gtfsTempFile.toAbsolutePath().toString(),
                    TimeZone.getTimeZone(agencyTimezone),
                    gtfsData,
                    false
            );

            List<GtfsRealtime.FeedMessage> feedMessages = fetchAndParseFeeds(gtfsRtUrls);
            GtfsRealtime.FeedMessage combinedFeed = buildCombinedFeed(feedMessages);

            long currentTimeMillis = System.currentTimeMillis();

            List<FeedValidationResult> feedResults = new ArrayList<>(feedMessages.size());
            for (int i = 0; i < feedMessages.size(); i++) {
                feedResults.add(validateFeed(
                        gtfsRtUrls.get(i), feedMessages.get(i), combinedFeed,
                        currentTimeMillis, gtfsData, gtfsMetadata));
            }

            return new ValidationResponse(gtfsUrl, currentTimeMillis, feedResults);
        } finally {
            if (gtfsTempFile != null) {
                try {
                    Files.deleteIfExists(gtfsTempFile);
                } catch (IOException e) {
                    log.debug("Could not delete temp file {}: {}", gtfsTempFile, e.getMessage());
                }
            }
        }
    }

    private FeedValidationResult validateFeed(String gtfsRtUrl, GtfsRealtime.FeedMessage feedMessage,
                                              GtfsRealtime.FeedMessage combinedFeed, long currentTimeMillis,
                                              GtfsDaoImpl gtfsData, GtfsMetadata gtfsMetadata) {
        long feedTimestampSeconds = feedMessage.hasHeader() ? feedMessage.getHeader().getTimestamp() : 0L;
        List<ValidationError> results = new ArrayList<>();
        List<SkippedRule> skippedRules = new ArrayList<>();
        for (FeedEntityValidator rule : validators) {
            String name = rule.getClass().getSimpleName();
            try {
                List<ErrorListHelperModel> ruleResults = rule.validate(
                        currentTimeMillis, gtfsData, gtfsMetadata, feedMessage, null, combinedFeed);
                if (ruleResults != null) {
                    ruleResults.stream().map(ValidationError::from).forEach(results::add);
                }
            } catch (Exception e) {
                skippedRules.add(new SkippedRule(name, e.getClass().getName(), e.getMessage()));
                log.error("Rule {} threw {} on {}", name, e.getClass().getSimpleName(), gtfsRtUrl, e);
            }
        }
        return new FeedValidationResult(gtfsRtUrl, feedTimestampSeconds, results, skippedRules);
    }

    /**
     * Returns the merged feed when the validator should perform cross-feed checks: either a single
     * URL that already mixes entity types, or multiple URLs that collectively do. Otherwise null.
     * Mirrors the logic in {@code BackgroundTask.run()}.
     */
    private static GtfsRealtime.FeedMessage buildCombinedFeed(List<GtfsRealtime.FeedMessage> feeds) {
        if (feeds.size() == 1) {
            GtfsRealtime.FeedMessage only = feeds.get(0);
            return GtfsUtils.isCombinedFeed(only) ? only : null;
        }
        GtfsRealtime.FeedHeader header = null;
        List<GtfsRealtime.FeedEntity> allEntities = new ArrayList<>();
        for (GtfsRealtime.FeedMessage msg : feeds) {
            if (msg.hasHeader() && (header == null || msg.getHeader().getTimestamp() > header.getTimestamp())) {
                header = msg.getHeader();
            }
            allEntities.addAll(msg.getEntityList());
        }
        GtfsRealtime.FeedMessage.Builder builder = GtfsRealtime.FeedMessage.newBuilder();
        if (header != null) {
            builder.setHeader(header);
        }
        builder.addAllEntity(allEntities);
        return builder.build();
    }

    private List<GtfsRealtime.FeedMessage> fetchAndParseFeeds(List<String> urls) throws ValidationException {
        int parallelism = Math.min(urls.size(), MAX_PARALLEL_FETCHES);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "gtfsrt-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<GtfsRealtime.FeedMessage>> futures = new ArrayList<>(urls.size());
            for (String url : urls) {
                futures.add(pool.submit(fetchAndParseTask(url)));
            }
            List<GtfsRealtime.FeedMessage> messages = new ArrayList<>(urls.size());
            for (Future<GtfsRealtime.FeedMessage> f : futures) {
                try {
                    messages.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ValidationException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while fetching feeds", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof ValidationException) {
                        throw (ValidationException) cause;
                    }
                    throw new ValidationException(HttpStatus.INTERNAL_SERVER_ERROR, "Feed fetch failed: " + cause.getMessage(), cause);
                }
            }
            return messages;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Callable<GtfsRealtime.FeedMessage> fetchAndParseTask(String url) {
        return () -> {
            byte[] bytes = downloadBytes(url);
            try {
                return GtfsRealtime.FeedMessage.parseFrom(bytes);
            } catch (IOException e) {
                throw new ValidationException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Failed to parse GTFS-realtime protobuf from " + url + ": " + e.getMessage(), e);
            }
        };
    }

    private static void requireHttpUrl(String value, String field) throws ValidationException {
        if (value == null || value.isBlank()) {
            throw new ValidationException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        URI uri;
        try {
            uri = new URI(value).parseServerAuthority();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ValidationException(HttpStatus.BAD_REQUEST, field + " is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ValidationException(HttpStatus.BAD_REQUEST, field + " must be an http or https URL");
        }
        if (uri.getHost() == null) {
            throw new ValidationException(HttpStatus.BAD_REQUEST, field + " must include a host");
        }
    }

    private static Path downloadToTempFile(String url) throws ValidationException {
        Path file;
        try {
            file = Files.createTempFile("gtfs-", ".zip");
        } catch (IOException e) {
            throw new ValidationException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create temp file: " + e.getMessage(), e);
        }
        file.toFile().deleteOnExit();
        try {
            withConnection(url, in -> {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return null;
            });
            return file;
        } catch (ValidationException e) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { /* best effort */ }
            throw e;
        }
    }

    private static byte[] downloadBytes(String url) throws ValidationException {
        return withConnection(url, InputStream::readAllBytes);
    }

    @FunctionalInterface
    private interface IOFunction<T, R> {
        R apply(T input) throws IOException;
    }

    private static <T> T withConnection(String url, IOFunction<InputStream, T> consumer) throws ValidationException {
        HttpURLConnection conn = openConnection(url);
        try (InputStream in = conn.getInputStream()) {
            return consumer.apply(in);
        } catch (IOException e) {
            throw new ValidationException(HttpStatus.BAD_GATEWAY, "Failed to download " + url + ": " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws ValidationException {
        try {
            URL parsed = new URI(url).toURL();
            HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "gtfs-realtime-validator-api");
            return conn;
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            throw new ValidationException(HttpStatus.BAD_GATEWAY, "Could not open connection to " + url + ": " + e.getMessage(), e);
        }
    }

    private static String firstAgencyTimezone(Collection<Agency> agencies) {
        for (Agency a : agencies) {
            if (a.getTimezone() != null && !a.getTimezone().isBlank()) {
                return a.getTimezone();
            }
        }
        return null;
    }
}
