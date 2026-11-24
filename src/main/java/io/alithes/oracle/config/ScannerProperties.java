package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the background scanner and per-scan concurrency limits ({@code oracle.scanner.*}).
 */
public record ScannerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("900000") long fixedDelayMs,
        @DefaultValue("30") int overlapMinutes,
        @DefaultValue("30") int maxArticlesPerScan,
        @DefaultValue("0.30") double headlineMinScore,
        @DefaultValue("8") int maxConcurrency
) {
    public ScannerProperties {
        PropertyAssertions.requirePositive(fixedDelayMs, "oracle.scanner.fixed-delay-ms");
        PropertyAssertions.requireNonNegative(overlapMinutes, "oracle.scanner.overlap-minutes");
        PropertyAssertions.requireNonNegative(maxArticlesPerScan, "oracle.scanner.max-articles-per-scan");
        PropertyAssertions.requireProbability(headlineMinScore, "oracle.scanner.headline-min-score");
        PropertyAssertions.requirePositive(maxConcurrency, "oracle.scanner.max-concurrency");
    }

    public static ScannerProperties defaults() {
        return new ScannerProperties(false, 900_000L, 30, 30, 0.30, 8);
    }
}
