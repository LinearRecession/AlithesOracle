package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for deterministic resolution policy quorum and dispute rules ({@code oracle.policy.*}).
 */
public record PolicyProperties(
        @DefaultValue("1") int version,
        @DefaultValue("0.55") double minClusterConfirmWeight,
        @DefaultValue("3") int minConfirmClusters,
        @DefaultValue("2") int minTrustedClusters,
        @DefaultValue("2.20") double minConfirmSum,
        @DefaultValue("0.50") double maxDenyRatio,
        @DefaultValue("1.50") double disputeThreshold,
        @DefaultValue("1.00") double postDeadlineDisputeConfirmSum,
        @DefaultValue("0.20") double maxFailedScanRatio,
        @DefaultValue("false") boolean jevSynthesisEnabled
) {
    public PolicyProperties {
        PropertyAssertions.requirePositive(version, "oracle.policy.version");
        PropertyAssertions.requireProbability(minClusterConfirmWeight, "oracle.policy.min-cluster-confirm-weight");
        PropertyAssertions.requirePositive(minConfirmClusters, "oracle.policy.min-confirm-clusters");
        PropertyAssertions.requireNonNegative(minTrustedClusters, "oracle.policy.min-trusted-clusters");
        PropertyAssertions.requirePositiveDouble(minConfirmSum, "oracle.policy.min-confirm-sum");
        PropertyAssertions.requireProbability(maxDenyRatio, "oracle.policy.max-deny-ratio");
        PropertyAssertions.requirePositiveDouble(disputeThreshold, "oracle.policy.dispute-threshold");
        PropertyAssertions.requireNonNegativeDouble(
                postDeadlineDisputeConfirmSum, "oracle.policy.post-deadline-dispute-confirm-sum");
        PropertyAssertions.requireProbability(maxFailedScanRatio, "oracle.policy.max-failed-scan-ratio");
    }

    public static PolicyProperties defaults() {
        return new PolicyProperties(1, 0.55, 3, 2, 2.20, 0.50, 1.50, 1.00, 0.20, false);
    }
}
