package io.alithes.oracle.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.GdeltProperties;
import io.alithes.oracle.config.OracleProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rate-limited HTTP client for the GDELT DOC 2.0 API ({@code /api/v2/doc/doc}).
 */
@Component
public class GdeltClient implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(GdeltClient.class);
    private static final DateTimeFormatter GDELT_PARAM_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GDELT_SEENDATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final GdeltProperties gdeltProps;
    private final String userAgent;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String baseUrl;
    private final ReentrantLock rateLimitLock = new ReentrantLock(true);

    private long lastRequestNanos = -1L;

    @Autowired
    public GdeltClient(
            OracleProperties props,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(
                Objects.requireNonNull(props, "props must not be null").gdelt(),
                props.fetcher().userAgent(),
                httpClient,
                objectMapper,
                clock
        );
    }

    public GdeltClient(GdeltProperties gdeltProps, HttpClient httpClient, ObjectMapper objectMapper) {
        this(gdeltProps, FetcherProperties.defaults().userAgent(), httpClient, objectMapper, Clock.systemUTC());
    }

    public GdeltClient(GdeltProperties gdeltProps, HttpClient httpClient, ObjectMapper objectMapper, Clock clock) {
        this(gdeltProps, FetcherProperties.defaults().userAgent(), httpClient, objectMapper, clock);
    }

    public GdeltClient(
            GdeltProperties gdeltProps,
            String userAgent,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.gdeltProps = Objects.requireNonNull(gdeltProps, "gdeltProps must not be null");
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? FetcherProperties.defaults().userAgent()
                : userAgent;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String rawBaseUrl = Objects.requireNonNull(gdeltProps.baseUrl(), "baseUrl must not be null");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
    }

    /**
     * Searches GDELT DOC 2.0 for articles matching {@code query} in {@code [from, to]}.
     * Automatically splits windows larger than {@code minSplitWindowMinutes} when saturated.
     */
    @Override
    public List<GdeltArticle> search(String query, Instant from, Instant to) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("GDELT query must not be blank");
        }
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            return List.of();
        }
        return searchWindow(query, from, to, 0);
    }

    private List<GdeltArticle> searchWindow(String query, Instant from, Instant to, int depth) {
        List<GdeltArticle> articles = executeSearchRequest(query, from, to);

        if (articles.size() >= gdeltProps.maxRecords()
                && Duration.between(from, to).toMinutes() > gdeltProps.minSplitWindowMinutes()
                && depth < gdeltProps.maxWindowSplitDepth()) {
            Instant mid = from.plus(Duration.between(from, to).dividedBy(2));
            log.info("GDELT returned {} results for [{}..{}]; splitting window at {} (depth={})",
                    articles.size(), from, to, mid, depth + 1);
            Map<String, GdeltArticle> merged = new LinkedHashMap<>();
            for (GdeltArticle article : searchWindow(query, from, mid, depth + 1)) {
                merged.putIfAbsent(article.url(), article);
            }
            for (GdeltArticle article : searchWindow(query, mid, to, depth + 1)) {
                merged.putIfAbsent(article.url(), article);
            }
            return List.copyOf(merged.values());
        }

        return articles;
    }

    private List<GdeltArticle> executeSearchRequest(String query, Instant from, Instant to) {
        URI uri = buildSearchUri(query, from, to);
        int maxAttempts = Math.max(0, gdeltProps.maxRetries()) + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(gdeltProps.timeoutMs()))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = sendRateLimited(request);
                int status = response.statusCode();
                String body = response.body() == null ? "" : response.body();

                if (status == 200) {
                    return parseResponseBody(body);
                }

                if (status == 429 || status >= 500) {
                    if (attempt < maxAttempts) {
                        long delayMs = exponentialBackoffMs(attempt);
                        log.warn("GDELT returned HTTP {}, retrying attempt {}/{} after {}ms",
                                status, attempt + 1, maxAttempts, delayMs);
                        sleepMs(delayMs);
                        continue;
                    }
                    throw new GdeltException("GDELT request failed with HTTP " + status + " after retries", status, body);
                }

                throw new GdeltException("GDELT request failed with HTTP " + status, status, body);
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    long delayMs = exponentialBackoffMs(attempt);
                    log.warn("GDELT I/O error ({}), retrying attempt {}/{} after {}ms",
                            e.getClass().getSimpleName(), attempt + 1, maxAttempts, delayMs);
                    sleepMs(delayMs);
                    continue;
                }
                throw new GdeltException("GDELT request failed: " + e.getMessage(), -1, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GdeltException("GDELT request interrupted", -1, null, e);
            }
        }

        throw new GdeltException("GDELT request exhausted attempts");
    }

    private HttpResponse<String> sendRateLimited(HttpRequest request)
            throws IOException, InterruptedException {
        rateLimitLock.lockInterruptibly();
        try {
            long minIntervalMs = gdeltProps.minIntervalMs();
            if (minIntervalMs > 0 && lastRequestNanos >= 0L) {
                long elapsedMs = (System.nanoTime() - lastRequestNanos) / 1_000_000L;
                long remainingMs = minIntervalMs - elapsedMs;
                if (remainingMs > 0) {
                    HttpRetrySupport.sleepInterruptibly(remainingMs);
                }
            }
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } finally {
                lastRequestNanos = System.nanoTime();
            }
        } finally {
            rateLimitLock.unlock();
        }
    }

    private URI buildSearchUri(String query, Instant from, Instant to) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/api/v2/doc/doc"
                + "?query=" + encodedQuery
                + "&mode=artlist"
                + "&format=json"
                + "&maxrecords=" + gdeltProps.maxRecords()
                + "&sort=datedesc"
                + "&startdatetime=" + GDELT_PARAM_FORMATTER.format(from)
                + "&enddatetime=" + GDELT_PARAM_FORMATTER.format(to);
        return URI.create(url);
    }

    private List<GdeltArticle> parseResponseBody(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (!trimmed.startsWith("{")) {
            throw new GdeltQueryException(trimmed);
        }

        try {
            GdeltDocResponse parsed = objectMapper.readValue(trimmed, GdeltDocResponse.class);
            if (parsed == null || parsed.articles() == null || parsed.articles().isEmpty()) {
                return List.of();
            }
            List<GdeltArticle> result = new ArrayList<>(parsed.articles().size());
            for (RawGdeltArticle raw : parsed.articles()) {
                if (raw == null || raw.url() == null || raw.url().isBlank()) {
                    continue;
                }
                result.add(new GdeltArticle(
                        raw.url().trim(),
                        raw.title() == null ? "" : raw.title(),
                        parseSeenDate(raw.seendate()),
                        raw.domain() == null ? "" : raw.domain(),
                        raw.language(),
                        raw.sourcecountry()
                ));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new GdeltException("Failed to parse GDELT response JSON", 200, body, e);
        }
    }

    private Instant parseSeenDate(String seenDate) {
        if (seenDate == null || seenDate.isBlank()) {
            return clock.instant();
        }
        String trimmed = seenDate.trim();
        try {
            return LocalDateTime.parse(trimmed, GDELT_SEENDATE_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException ignoredToo) {
                return clock.instant();
            }
        }
    }

    private long exponentialBackoffMs(int attempt) {
        return HttpRetrySupport.exponentialBackoffMs(
                gdeltProps.retryBaseDelayMs(), attempt, HttpRetrySupport.DEFAULT_MAX_BACKOFF_MS);
    }

    private static void sleepMs(long delayMs) {
        try {
            HttpRetrySupport.sleepInterruptibly(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GdeltException("GDELT retry sleep interrupted", -1, null, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GdeltDocResponse(List<RawGdeltArticle> articles) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawGdeltArticle(
            String url,
            String title,
            String seendate,
            String domain,
            String language,
            String sourcecountry
    ) {
    }
}
