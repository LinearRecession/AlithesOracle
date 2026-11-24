package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for deterministic evidence scoring, deduplication clustering, and confidence scaling
 * ({@code oracle.scoring.*}).
 */
public record ScoringProperties(
        @DefaultValue("0.30") double minStanceFits,
        @DefaultValue("0.50") double minStanceConfidence,
        @DefaultValue("0.50") double minFreshProbability,
        @DefaultValue("0.70") double trustedTierThreshold,
        @DefaultValue("0.40") double titleOnlyFetchFactor,
        @DefaultValue("0.50") double unstableStanceFactor,
        @DefaultValue("0.50") double disputedConfidence,
        @DefaultValue("0.99") double maxResolutionConfidence,
        @DefaultValue("0.50") double minNoConfidence,
        @DefaultValue("3.0") double confidenceScaleSum,
        @DefaultValue("3") int maxSimhashDistance,
        @DefaultValue("5") int topClusterLimit,
        @DefaultValue("24") long freshLookbackHours,
        @DefaultValue("0.30") double defaultTierWeight,
        @DefaultValue("/source-tiers.properties") String tiersResourcePath
) {
    public ScoringProperties {
        PropertyAssertions.requireProbability(minStanceFits, "oracle.scoring.min-stance-fits");
        PropertyAssertions.requireProbability(minStanceConfidence, "oracle.scoring.min-stance-confidence");
        PropertyAssertions.requireProbability(minFreshProbability, "oracle.scoring.min-fresh-probability");
        PropertyAssertions.requireProbability(trustedTierThreshold, "oracle.scoring.trusted-tier-threshold");
        PropertyAssertions.requireProbability(titleOnlyFetchFactor, "oracle.scoring.title-only-fetch-factor");
        PropertyAssertions.requireProbability(unstableStanceFactor, "oracle.scoring.unstable-stance-factor");
        PropertyAssertions.requireProbability(disputedConfidence, "oracle.scoring.disputed-confidence");
        PropertyAssertions.requireProbability(maxResolutionConfidence, "oracle.scoring.max-resolution-confidence");
        PropertyAssertions.requireProbability(minNoConfidence, "oracle.scoring.min-no-confidence");
        PropertyAssertions.requirePositiveDouble(confidenceScaleSum, "oracle.scoring.confidence-scale-sum");
        PropertyAssertions.requireNonNegative(maxSimhashDistance, "oracle.scoring.max-simhash-distance");
        PropertyAssertions.requirePositive(topClusterLimit, "oracle.scoring.top-cluster-limit");
        PropertyAssertions.requireNonNegative(freshLookbackHours, "oracle.scoring.fresh-lookback-hours");
        PropertyAssertions.requireProbability(defaultTierWeight, "oracle.scoring.default-tier-weight");
        PropertyAssertions.requireNonBlank(tiersResourcePath, "oracle.scoring.tiers-resource-path");
    }

    public static ScoringProperties defaults() {
        return new ScoringProperties(
                0.30, 0.50, 0.50, 0.70, 0.40, 0.50, 0.50, 0.99, 0.50, 3.0, 3, 5, 24L, 0.30,
                "/source-tiers.properties"
        );
    }
}
