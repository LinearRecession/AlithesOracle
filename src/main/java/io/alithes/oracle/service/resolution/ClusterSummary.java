package io.alithes.oracle.service.resolution;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated independent-cluster metrics fed into the {@link ResolutionRule} policy chain.
 */
public record ClusterSummary(
        int independentClusters,
        long trustedConfirmClusters,
        double confirmSum,
        double denySum,
        List<UUID> supportingIds
) {
}
