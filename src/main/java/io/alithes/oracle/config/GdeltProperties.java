package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the GDELT DOC 2.0 client ({@code oracle.gdelt.*}).
 */
public record GdeltProperties(
        @DefaultValue("https://api.gdeltproject.org") String baseUrl,
        @DefaultValue("5500") long minIntervalMs,
        @DefaultValue("15000") long timeoutMs,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("6000") long retryBaseDelayMs,
        @DefaultValue("3") int maxWindowSplitDepth,
        @DefaultValue("250") int maxRecords,
        @DefaultValue("60") int minSplitWindowMinutes
) {
    @ConstructorBinding
    public GdeltProperties {
        PropertyAssertions.requireNonNegative(minIntervalMs, "oracle.gdelt.min-interval-ms");
        PropertyAssertions.requirePositive(timeoutMs, "oracle.gdelt.timeout-ms");
        PropertyAssertions.requireNonNegative(maxRetries, "oracle.gdelt.max-retries");
        PropertyAssertions.requireNonNegative(retryBaseDelayMs, "oracle.gdelt.retry-base-delay-ms");
        PropertyAssertions.requireNonNegative(maxWindowSplitDepth, "oracle.gdelt.max-window-split-depth");
        PropertyAssertions.requirePositive(maxRecords, "oracle.gdelt.max-records");
        PropertyAssertions.requirePositive(minSplitWindowMinutes, "oracle.gdelt.min-split-window-minutes");
    }

    public GdeltProperties(
            String baseUrl,
            long minIntervalMs,
            long timeoutMs,
            int maxRetries,
            long retryBaseDelayMs,
            int maxWindowSplitDepth
    ) {
        this(baseUrl, minIntervalMs, timeoutMs, maxRetries, retryBaseDelayMs, maxWindowSplitDepth, 250, 60);
    }

    public static GdeltProperties defaults() {
        return new GdeltProperties(
                "https://api.gdeltproject.org",
                5_500L,
                15_000L,
                3,
                6_000L,
                3,
                250,
                60
        );
    }
}
