package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;

/**
 * Deterministic scoring projection of a single {@link Evidence} item and its {@link Assessment}.
 */
public record ScoredEvidence(
        Evidence evidence,
        Assessment assessment,
        double tier,
        double confirmWeight,
        double denyWeight,
        String normalizedTitle,
        String canonicalDomain,
        long simhash
) {
    public double maxWeight() {
        return Math.max(confirmWeight, denyWeight);
    }

    public boolean isTrusted(double trustedTierThreshold) {
        return tier >= trustedTierThreshold;
    }
}
