package io.alithes.oracle.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.util.CryptoUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HTTP client for TypeSafe AI Jev {@code POST /v1/systemone}.
 */
@Component
public class JevClient implements DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(JevClient.class);
    private static final long MAX_RETRY_AFTER_MS = 30_000L;

    private final OracleProperties.Jev jevProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpointUri;

    @Autowired
    public JevClient(OracleProperties props, HttpClient httpClient, ObjectMapper objectMapper) {
        this(Objects.requireNonNull(props, "props must not be null").jev(), httpClient, objectMapper);
    }

    public JevClient(OracleProperties.Jev jevProps, HttpClient httpClient, ObjectMapper objectMapper) {
        this.jevProps = Objects.requireNonNull(jevProps, "jevProps must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");

        String model = jevProps.model() == null ? "" : jevProps.model().trim();
        if (jevProps.rejectAlias() && ("jev-latest".equals(model) || "jev-preview".equals(model))) {
            throw new IllegalStateException(
                    "Aliased Jev model '" + model + "' is forbidden when oracle.jev.reject-alias=true; "
                            + "pin a versioned model ID such as jev-1.14.0"
            );
        }

        String baseUrl = stripTrailingSlash(Objects.requireNonNull(jevProps.baseUrl(), "baseUrl must not be null"));
        this.endpointUri = URI.create(baseUrl + "/v1/systemone");
    }

    /**
     * Evaluates one or more questions against the provided state via {@code POST /v1/systemone}.
     */
    @Override
    public JevModels.JevResponse evaluate(Object state, Map<String, JevModels.QuestionSpec> questions) {
        Objects.requireNonNull(state, "state must not be null");
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }

        var request = new JevModels.SystemOneRequest(
                jevProps.model(),
                state,
                new LinkedHashMap<>(questions)
        );

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new JevException("Failed to serialize Jev request", -1, null, e);
        }

        String requestSha256 = CryptoUtil.sha256Hex(requestJson.getBytes(StandardCharsets.UTF_8));
        String apiKey = jevProps.apiKey() == null ? "" : jevProps.apiKey();
        int maxAttempts = Math.max(0, jevProps.maxRetries()) + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(endpointUri)
                    .timeout(Duration.ofMillis(jevProps.timeoutMs()))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                int status = response.statusCode();
                String body = response.body() == null ? "" : response.body();

                if (status == 200) {
                    return parseSuccessResponse(body, requestSha256);
                }

                if (status == 429 || status == 529) {
                    if (attempt < maxAttempts) {
                        long delayMs = resolveRetryAfterMs(response, attempt);
                        log.warn("Jev API returned HTTP {}, retrying attempt {}/{} after {}ms",
                                status, attempt + 1, maxAttempts, delayMs);
                        sleepMs(delayMs);
                        continue;
                    }
                    throw new JevException("Jev API returned HTTP " + status + " after retries", status, body);
                }

                if (status == 400 && body.contains("max_tokens_exceeded")) {
                    throw new JevContextTooLargeException("Jev request exceeded maximum context tokens", body);
                }

                if (status == 400 || status == 401 || status == 413 || status == 415 || status == 422
                        || status < 500) {
                    throw new JevException("Jev API non-retryable error HTTP " + status, status, body);
                }

                // 5xx server error
                if (attempt < maxAttempts) {
                    long delayMs = exponentialBackoffMs(attempt);
                    log.warn("Jev API returned HTTP {}, retrying attempt {}/{} after {}ms",
                            status, attempt + 1, maxAttempts, delayMs);
                    sleepMs(delayMs);
                    continue;
                }
                throw new JevException("Jev API server error HTTP " + status + " after retries", status, body);
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    long delayMs = exponentialBackoffMs(attempt);
                    log.warn("Jev API I/O error ({}), retrying attempt {}/{} after {}ms",
                            e.getClass().getSimpleName(), attempt + 1, maxAttempts, delayMs);
                    sleepMs(delayMs);
                    continue;
                }
                throw new JevException("Jev API request failed: " + e.getMessage(), -1, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JevException("Jev API request interrupted", -1, null, e);
            }
        }

        throw new JevException("Jev API request exhausted attempts", -1, null);
    }

    private JevModels.JevResponse parseSuccessResponse(String body, String requestSha256) {
        try {
            JevModels.SystemOneResponse parsed = objectMapper.readValue(body, JevModels.SystemOneResponse.class);
            if (parsed == null || parsed.answers() == null) {
                throw new JevException("Jev API response missing answers field", 200, body);
            }
            int inputTokens = parsed.usage() != null ? parsed.usage().inputTokens() : 0;
            int outputTokens = parsed.usage() != null ? parsed.usage().outputTokens() : 0;
            return new JevModels.JevResponse(
                    parsed.model(),
                    parsed.answers(),
                    inputTokens,
                    outputTokens,
                    requestSha256,
                    body
            );
        } catch (JsonProcessingException e) {
            throw new JevException("Failed to parse Jev API response JSON", 200, body, e);
        }
    }

    private long resolveRetryAfterMs(HttpResponse<?> response, int attempt) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                long seconds = Long.parseLong(header.get().trim());
                if (seconds >= 0) {
                    return seconds >= 30L ? MAX_RETRY_AFTER_MS : seconds * 1000L;
                }
            } catch (NumberFormatException ignored) {
                // Fall back to exponential backoff when Retry-After is not an integer.
            }
        }
        return exponentialBackoffMs(attempt);
    }

    private long exponentialBackoffMs(int attempt) {
        return HttpRetrySupport.exponentialBackoffMs(
                jevProps.retryBaseDelayMs(), attempt, HttpRetrySupport.DEFAULT_MAX_BACKOFF_MS);
    }

    private static void sleepMs(long delayMs) {
        try {
            HttpRetrySupport.sleepInterruptibly(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevException("Jev API retry sleep interrupted", -1, null, e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
