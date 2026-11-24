package io.alithes.oracle.service.resolution;

import io.alithes.oracle.util.SimHash;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mutable syndication/wire-copy cluster aggregating matching scored evidence items into
 * a single independent vote.
 */
final class EvidenceCluster {

    private final List<ScoredEvidence> members = new ArrayList<>();
    private final int maxSimhashDistance;
    private final double trustedTierThreshold;
    private double confirmWeight;
    private double denyWeight;
    private boolean trusted;
    private UUID bestEvidenceId;
    private double bestConfirmWeight = -1.0;

    EvidenceCluster(ScoredEvidence initial, int maxSimhashDistance, double trustedTierThreshold) {
        this.maxSimhashDistance = maxSimhashDistance;
        this.trustedTierThreshold = trustedTierThreshold;
        add(initial);
    }

    boolean matches(ScoredEvidence candidate) {
        for (ScoredEvidence member : members) {
            if (candidate.simhash() != 0L
                    && member.simhash() != 0L
                    && SimHash.hammingDistance(candidate.simhash(), member.simhash()) <= maxSimhashDistance) {
                return true;
            }
            if (!candidate.normalizedTitle().isEmpty()
                    && candidate.normalizedTitle().equals(member.normalizedTitle())) {
                return true;
            }
            if (!candidate.canonicalDomain().isEmpty()
                    && candidate.canonicalDomain().equals(member.canonicalDomain())) {
                return true;
            }
        }
        return false;
    }

    void add(ScoredEvidence item) {
        members.add(item);
        if (item.confirmWeight() > confirmWeight) {
            confirmWeight = item.confirmWeight();
        }
        if (item.denyWeight() > denyWeight) {
            denyWeight = item.denyWeight();
        }
        if (item.isTrusted(trustedTierThreshold)) {
            trusted = true;
        }
        UUID evidenceId = item.evidence().id();
        if (bestEvidenceId == null
                || item.confirmWeight() > bestConfirmWeight
                || (item.confirmWeight() == bestConfirmWeight
                        && evidenceId != null
                        && evidenceId.compareTo(bestEvidenceId) < 0)) {
            bestConfirmWeight = item.confirmWeight();
            bestEvidenceId = evidenceId;
        }
    }

    double confirmWeight() {
        return confirmWeight;
    }

    double denyWeight() {
        return denyWeight;
    }

    boolean isTrusted() {
        return trusted;
    }

    UUID bestEvidenceId() {
        return bestEvidenceId;
    }
}
