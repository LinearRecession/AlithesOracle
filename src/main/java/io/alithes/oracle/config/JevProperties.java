package io.alithes.oracle.config;

import java.util.Set;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the TypeSafe AI Jev client ({@code oracle.jev.*}).
 */
public record JevProperties(
        @DefaultValue("https://api.typesafe.ai") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("jev-1.14.0") String model,
        @DefaultValue("true") boolean rejectAlias,
        @DefaultValue("15000") long timeoutMs,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("1000") long retryBaseDelayMs,
        @DefaultValue("30000") long maxRetryAfterMs,
        @DefaultValue({"jev-latest", "jev-preview"}) Set<String> forbiddenAliases
) {
    public static final Set<String> DEFAULT_FORBIDDEN_ALIASES = Set.of("jev-latest", "jev-preview");

    @ConstructorBinding
    public JevProperties {
        PropertyAssertions.requirePositive(timeoutMs, "oracle.jev.timeout-ms");
        PropertyAssertions.requireNonNegative(maxRetries, "oracle.jev.max-retries");
        PropertyAssertions.requireNonNegative(retryBaseDelayMs, "oracle.jev.retry-base-delay-ms");
        PropertyAssertions.requirePositive(maxRetryAfterMs, "oracle.jev.max-retry-after-ms");
        forbiddenAliases = forbiddenAliases == null ? DEFAULT_FORBIDDEN_ALIASES : Set.copyOf(forbiddenAliases);
    }

    public JevProperties(
            String baseUrl,
            String apiKey,
            String model,
            boolean rejectAlias,
            long timeoutMs,
            int maxRetries,
            long retryBaseDelayMs
    ) {
        this(baseUrl, apiKey, model, rejectAlias, timeoutMs, maxRetries, retryBaseDelayMs,
                30_000L, DEFAULT_FORBIDDEN_ALIASES);
    }

    public static JevProperties defaults() {
        return new JevProperties(
                "https://api.typesafe.ai",
                "",
                "jev-1.14.0",
                true,
                15_000L,
                3,
                1_000L,
                30_000L,
                DEFAULT_FORBIDDEN_ALIASES
        );
    }
}
