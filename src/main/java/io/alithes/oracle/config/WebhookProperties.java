package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for outgoing HMAC-signed webhook delivery ({@code oracle.webhook.*}).
 */
public record WebhookProperties(
        @DefaultValue("dev-secret-change-me") String secret,
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("100") long retryBaseDelayMs,
        @DefaultValue("5000") long maxBackoffMs,
        @DefaultValue("X-Alithes-Signature") String signatureHeader
) {
    @ConstructorBinding
    public WebhookProperties {
        PropertyAssertions.requirePositive(timeoutMs, "oracle.webhook.timeout-ms");
        PropertyAssertions.requireNonNegative(maxRetries, "oracle.webhook.max-retries");
        PropertyAssertions.requireNonNegative(retryBaseDelayMs, "oracle.webhook.retry-base-delay-ms");
        PropertyAssertions.requirePositive(maxBackoffMs, "oracle.webhook.max-backoff-ms");
        PropertyAssertions.requireNonBlank(signatureHeader, "oracle.webhook.signature-header");
    }

    public WebhookProperties(String secret, long timeoutMs, int maxRetries) {
        this(secret, timeoutMs, maxRetries, 100L, 5_000L, "X-Alithes-Signature");
    }

    public static WebhookProperties defaults() {
        return new WebhookProperties("dev-secret-change-me", 5_000L, 3, 100L, 5_000L, "X-Alithes-Signature");
    }
}
