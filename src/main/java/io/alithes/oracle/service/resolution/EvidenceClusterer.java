package io.alithes.oracle.service.resolution;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.ScoringProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Groups near-duplicate / same-publisher evidence into independent clusters and computes
 * {@link ClusterSummary} quorum aggregates.
 */
@Component
public class EvidenceClusterer {

    private static final Comparator<EvidenceCluster> CLUSTER_BY_CONFIRM_DESC =
            Comparator.comparingDouble(EvidenceCluster::confirmWeight).reversed()
                    .thenComparing(EvidenceCluster::bestEvidenceId,
                            Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private static final Comparator<EvidenceCluster> CLUSTER_BY_DENY_DESC =
            Comparator.comparingDouble(EvidenceCluster::denyWeight).reversed()
                    .thenComparing(EvidenceCluster::bestEvidenceId,
                            Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private final ScoringProperties scoringProps;

    @Autowired
    public EvidenceClusterer(OracleProperties props) {
        this(Objects.requireNonNull(props, "props must not be null").scoring());
    }

    public EvidenceClusterer(ScoringProperties scoringProps) {
        this.scoringProps = Objects.requireNonNull(scoringProps, "scoringProps must not be null");
    }

    public ClusterSummary clusterAndSummarize(List<ScoredEvidence> sortedScoredItems, PolicyProperties policy) {
        List<EvidenceCluster> clusters = clusterItems(sortedScoredItems);

        List<EvidenceCluster> confirmClusters = clusters.stream()
                .filter(c -> c.confirmWeight() >= policy.minClusterConfirmWeight())
                .sorted(CLUSTER_BY_CONFIRM_DESC)
                .toList();

        long trustedConfirmClusters = confirmClusters.stream()
                .filter(EvidenceCluster::isTrusted)
                .count();

        int topLimit = scoringProps.topClusterLimit();
        double confirmSum = clusters.stream()
                .sorted(CLUSTER_BY_CONFIRM_DESC)
                .limit(topLimit)
                .mapToDouble(EvidenceCluster::confirmWeight)
                .sum();

        double denySum = clusters.stream()
                .sorted(CLUSTER_BY_DENY_DESC)
                .limit(topLimit)
                .mapToDouble(EvidenceCluster::denyWeight)
                .sum();

        List<UUID> supportingIds = confirmClusters.stream()
                .limit(topLimit)
                .map(EvidenceCluster::bestEvidenceId)
                .filter(Objects::nonNull)
                .toList();

        return new ClusterSummary(
                confirmClusters.size(),
                trustedConfirmClusters,
                confirmSum,
                denySum,
                supportingIds
        );
    }

    private List<EvidenceCluster> clusterItems(List<ScoredEvidence> sortedScoredItems) {
        List<EvidenceCluster> clusters = new ArrayList<>();
        int maxDistance = scoringProps.maxSimhashDistance();
        double trustedThreshold = scoringProps.trustedTierThreshold();
        for (ScoredEvidence item : sortedScoredItems) {
            if (item.confirmWeight() <= 0.0 && item.denyWeight() <= 0.0) {
                continue;
            }
            EvidenceCluster target = null;
            for (EvidenceCluster cluster : clusters) {
                if (cluster.matches(item)) {
                    target = cluster;
                    break;
                }
            }
            if (target != null) {
                target.add(item);
            } else {
                clusters.add(new EvidenceCluster(item, maxDistance, trustedThreshold));
            }
        }
        return clusters;
    }
}
