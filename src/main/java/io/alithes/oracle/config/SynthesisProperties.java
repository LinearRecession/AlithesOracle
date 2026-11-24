package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for Jev consensus synthesis and post-deadline adjudication ({@code oracle.synthesis.*}).
 */
public record SynthesisProperties(
        @DefaultValue("8") int maxReports,
        @DefaultValue("400") int maxExcerptChars,
        @DefaultValue("0.50") double minRelevantProbability,
        @DefaultValue("0.70") double minStrongStanceProbability,
        @DefaultValue("3") int minDistinctDomains,
        @DefaultValue("5") int maxSupportingIds,
        @DefaultValue("0.65") double minClearProbability,
        @DefaultValue("0.75") double minConsensusProbability,
        @DefaultValue("0.25") double maxOpposingProbability,
        @DefaultValue("0.95") double maxConfidence
) {
    public SynthesisProperties {
        PropertyAssertions.requirePositive(maxReports, "oracle.synthesis.max-reports");
        PropertyAssertions.requirePositive(maxExcerptChars, "oracle.synthesis.max-excerpt-chars");
        PropertyAssertions.requireProbability(minRelevantProbability, "oracle.synthesis.min-relevant-probability");
        PropertyAssertions.requireProbability(
                minStrongStanceProbability, "oracle.synthesis.min-strong-stance-probability");
        PropertyAssertions.requirePositive(minDistinctDomains, "oracle.synthesis.min-distinct-domains");
        PropertyAssertions.requirePositive(maxSupportingIds, "oracle.synthesis.max-supporting-ids");
        PropertyAssertions.requireProbability(minClearProbability, "oracle.synthesis.min-clear-probability");
        PropertyAssertions.requireProbability(minConsensusProbability, "oracle.synthesis.min-consensus-probability");
        PropertyAssertions.requireProbability(maxOpposingProbability, "oracle.synthesis.max-opposing-probability");
        PropertyAssertions.requireProbability(maxConfidence, "oracle.synthesis.max-confidence");
    }

    public static SynthesisProperties defaults() {
        return new SynthesisProperties(8, 400, 0.50, 0.70, 3, 5, 0.65, 0.75, 0.25, 0.95);
    }
}
