package io.alithes.oracle.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.client.HttpRetrySupport;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.util.CryptoUtil;
import io.alithes.oracle.util.SafeUrlValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Delivers HMAC-SHA256 signed resolution webhooks with bounded retries and non-fatal failure handling.
 */
@Service
public class WebhookNotifier implements ResolutionNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final String SIGNATURE_HEADER = "X-Alithes-Signature";
    private static final long RETRY_BASE_DELAY_MS = 100L;

    private final OracleProperties.Webhook webhookProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SafeUrlValidator urlValidator;

    @Autowired
    public WebhookNotifier(
            OracleProperties props,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            SafeUrlValidator urlValidator
    ) {
        this.webhookProps = Objects.requireNonNull(props, "props must not be null").webhook();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.urlValidator = Objects.requireNonNull(urlValidator, "urlValidator must not be null");
    }

    /**
     * Sends {@code resolution} as signed JSON to {@code webhookUrl}. Never throws on failure.
     */
    @Override
    public void notify(String webhookUrl, Resolution resolution) {
        if (webhookUrl == null || webhookUrl.isBlank() || resolution == null) {
            return;
        }

        final URI targetUri;
        final byte[] bodyBytes;
        final String signatureHeaderValue;
        try {
            targetUri = urlValidator.validateAndParse(webhookUrl);
            bodyBytes = objectMapper.writeValueAsBytes(resolution);
            String secret = webhookProps.secret() == null ? "" : webhookProps.secret();
            signatureHeaderValue = "sha256=" + CryptoUtil.hmacSha256Hex(secret, bodyBytes);
        } catch (Exception e) {
            log.warn("Failed to prepare webhook notification for question {} to {}: {}",
                    resolution.questionId(), webhookUrl, e.getMessage());
            return;
        }

        int maxAttempts = Math.max(0, webhookProps.maxRetries()) + 1;
        Duration timeout = Duration.ofMillis(Math.max(100L, webhookProps.timeoutMs()));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header(SIGNATURE_HEADER, signatureHeaderValue)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    log.info("Delivered webhook for question {} (outcome={}) to {} with HTTP {}",
                            resolution.questionId(), resolution.outcome(), targetUri, status);
                    return;
                }

                log.warn("Webhook attempt {}/{} for question {} returned HTTP {}",
                        attempt, maxAttempts, resolution.questionId(), status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Webhook delivery interrupted for question {} to {}",
                        resolution.questionId(), targetUri);
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("Webhook attempt {}/{} for question {} failed: {}",
                        attempt, maxAttempts, resolution.questionId(), e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    HttpRetrySupport.sleepInterruptibly(
                            HttpRetrySupport.exponentialBackoffMs(RETRY_BASE_DELAY_MS, attempt, 5000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.warn("Exhausted {} webhook delivery attempts for question {} to {}",
                maxAttempts, resolution.questionId(), targetUri);
    }

    static String hmacSha256Hex(String secret, byte[] payload) {
        return CryptoUtil.hmacSha256Hex(secret, payload);
    }
}
