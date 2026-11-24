package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for question admission gates and query lookback/grace limits ({@code oracle.admission.*}).
 */
public record AdmissionProperties(
        @DefaultValue("10") int minTextLength,
        @DefaultValue("500") int maxTextLength,
        @DefaultValue("80") int maxLookbackDays,
        @DefaultValue("0.60") double minDiscreteEventProb,
        @DefaultValue("0.65") double maxAmbiguousScore,
        @DefaultValue("0.50") double minNewsworthyScore,
        @DefaultValue("48") int defaultGraceHours,
        @DefaultValue("720") int maxGraceHours,
        @DefaultValue("30") int defaultQueryLookbackDays,
        @DefaultValue("79") int maxQueryLookbackDays
) {
    public AdmissionProperties {
        PropertyAssertions.requirePositive(minTextLength, "oracle.admission.min-text-length");
        if (maxTextLength < minTextLength) {
            throw new IllegalArgumentException(
                    "oracle.admission.max-text-length must be >= min-text-length, got " + maxTextLength);
        }
        PropertyAssertions.requirePositive(maxLookbackDays, "oracle.admission.max-lookback-days");
        PropertyAssertions.requireProbability(minDiscreteEventProb, "oracle.admission.min-discrete-event-prob");
        PropertyAssertions.requireProbability(maxAmbiguousScore, "oracle.admission.max-ambiguous-score");
        PropertyAssertions.requireProbability(minNewsworthyScore, "oracle.admission.min-newsworthy-score");
        PropertyAssertions.requireNonNegative(defaultGraceHours, "oracle.admission.default-grace-hours");
        if (maxGraceHours < defaultGraceHours) {
            throw new IllegalArgumentException(
                    "oracle.admission.max-grace-hours must be >= default-grace-hours, got " + maxGraceHours);
        }
        PropertyAssertions.requirePositive(defaultQueryLookbackDays, "oracle.admission.default-query-lookback-days");
        if (maxQueryLookbackDays < defaultQueryLookbackDays) {
            throw new IllegalArgumentException(
                    "oracle.admission.max-query-lookback-days must be >= default-query-lookback-days, got "
                            + maxQueryLookbackDays);
        }
    }

    public static AdmissionProperties defaults() {
        return new AdmissionProperties(10, 500, 80, 0.60, 0.65, 0.50, 48, 720, 30, 79);
    }
}
