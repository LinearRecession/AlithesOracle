package io.alithes.oracle.service;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.util.SimHash;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic policy engine that scores, clusters, and evaluates assessed evidence
 * into a {@link Resolution} (or {@link Optional#empty()} while the question remains active).
 */
@Component
public class ResolutionEngine {

    private static final double MIN_STANCE_FITS = 0.30;
    private static final double MIN_STANCE_CONFIDENCE = 0.50;
    private static final double MIN_FRESH_PROBABILITY = 0.50;
    private static final double TRUSTED_TIER_THRESHOLD = 0.70;
    private static final double TITLE_ONLY_FETCH_FACTOR = 0.40;
    private static final double UNSTABLE_STANCE_FACTOR = 0.50;
    private static final double DISPUTED_CONFIDENCE = 0.50;
    private static final double MAX_RESOLUTION_CONFIDENCE = 0.99;
    private static final double MIN_NO_CONFIDENCE = 0.50;
    private static final double CONFIDENCE_SCALE_SUM = 3.0;
    private static final int MAX_SIMHASH_DISTANCE = 3;
    private static final int TOP_CLUSTER_LIMIT = 5;
    private static final Duration FRESH_LOOKBACK = Duration.ofHours(24);

    private static final Comparator<ScoredItem> SCORED_ITEM_ORDER =
            Comparator.comparingDouble(ScoredItem::maxWeight).reversed()
                    .thenComparing(Comparator.comparingDouble(ScoredItem::confirmWeight).reversed())
                    .thenComparing(s -> s.evidence().id(), Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private static final Comparator<Cluster> CLUSTER_BY_CONFIRM_DESC =
            Comparator.comparingDouble((Cluster c) -> c.confirmWeight).reversed()
                    .thenComparing(c -> c.bestEvidenceId, Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private static final Comparator<Cluster> CLUSTER_BY_DENY_DESC =
            Comparator.comparingDouble((Cluster c) -> c.denyWeight).reversed()
                    .thenComparing(c -> c.bestEvidenceId, Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    public record EvidenceWithAssessment(Evidence evidence, Assessment assessment) {
    }

    private final OracleProperties.Policy policy;
    private final SourceTierRegistry sourceTierRegistry;

    @Autowired
    public ResolutionEngine(OracleProperties props, SourceTierRegistry sourceTierRegistry) {
        this(Objects.requireNonNull(props, "props must not be null").policy(), sourceTierRegistry);
    }

    public ResolutionEngine(OracleProperties.Policy policy, SourceTierRegistry sourceTierRegistry) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.sourceTierRegistry = Objects.requireNonNull(sourceTierRegistry, "sourceTierRegistry must not be null");
    }

    /**
     * Evaluates all assessed evidence and GDELT scan logs for a question at {@code now}.
     */
    public Optional<Resolution> evaluate(
            Question q,
            List<EvidenceWithAssessment> items,
            List<ScanLog> scanLogs,
            Instant now
    ) {
        Objects.requireNonNull(q, "question must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<EvidenceWithAssessment> safeItems = items == null ? List.of() : items;
        List<ScanLog> safeScanLogs = scanLogs == null ? List.of() : scanLogs;

        List<ScoredItem> scoredItems = scoreItems(q, safeItems);
        String model = selectModel(scoredItems);
        List<Cluster> clusters = clusterItems(scoredItems);
        ClusterSummary summary = summarizeClusters(clusters);

        return applyPolicy(q, summary, safeScanLogs, model, now);
    }

    private List<ScoredItem> scoreItems(Question q, List<EvidenceWithAssessment> items) {
        List<ScoredItem> scored = new ArrayList<>(items.size());
        for (EvidenceWithAssessment item : items) {
            if (item == null || item.evidence() == null || item.assessment() == null) {
                continue;
            }
            Evidence ev = item.evidence();
            Assessment a = item.assessment();

            double tier;
            double confirmWeight;
            double denyWeight;
            if (a.injectionFlag() || a.pStanceFits() < MIN_STANCE_FITS) {
                tier = 0.0;
                confirmWeight = 0.0;
                denyWeight = 0.0;
            } else {
                tier = sourceTierRegistry.weightFor(ev.domain());
                double attr = attributionWeight(a.attribution());
                double fetch = (ev.fetchStatus() == FetchStatus.OK) ? 1.0 : TITLE_ONLY_FETCH_FACTOR;
                double stability = (a.stanceUnstable() || a.stanceConfidence() < MIN_STANCE_CONFIDENCE)
                        ? UNSTABLE_STANCE_FACTOR
                        : 1.0;
                double fresh = freshnessWeight(ev, q, a);
                double base = a.pRelevant() * fresh * tier * attr * fetch * stability;
                confirmWeight = base * a.pConfirms();
                denyWeight = base * a.pDenies();
            }

            scored.add(new ScoredItem(
                    ev,
                    a,
                    tier,
                    confirmWeight,
                    denyWeight,
                    normalizeTitle(ev.title()),
                    sourceTierRegistry.canonicalDomain(ev.domain()),
                    ev.simhash()
            ));
        }
        scored.sort(SCORED_ITEM_ORDER);
        return scored;
    }

    private List<Cluster> clusterItems(List<ScoredItem> sortedScoredItems) {
        List<Cluster> clusters = new ArrayList<>();
        for (ScoredItem item : sortedScoredItems) {
            // Zero-weight items (rejected by injection/stance/date/tier gates) do not affect clusters or trust.
            if (item.confirmWeight() <= 0.0 && item.denyWeight() <= 0.0) {
                continue;
            }
            Cluster target = null;
            for (Cluster cluster : clusters) {
                if (cluster.matches(item)) {
                    target = cluster;
                    break;
                }
            }
            if (target != null) {
                target.add(item);
            } else {
                clusters.add(new Cluster(item));
            }
        }
        return clusters;
    }

    private ClusterSummary summarizeClusters(List<Cluster> clusters) {
        List<Cluster> confirmClusters = clusters.stream()
                .filter(c -> c.confirmWeight >= policy.minClusterConfirmWeight())
                .sorted(CLUSTER_BY_CONFIRM_DESC)
                .toList();

        long trustedConfirmClusters = confirmClusters.stream()
                .filter(c -> c.trusted)
                .count();

        double confirmSum = clusters.stream()
                .sorted(CLUSTER_BY_CONFIRM_DESC)
                .limit(TOP_CLUSTER_LIMIT)
                .mapToDouble(c -> c.confirmWeight)
                .sum();

        double denySum = clusters.stream()
                .sorted(CLUSTER_BY_DENY_DESC)
                .limit(TOP_CLUSTER_LIMIT)
                .mapToDouble(c -> c.denyWeight)
                .sum();

        List<UUID> supportingIds = confirmClusters.stream()
                .limit(TOP_CLUSTER_LIMIT)
                .map(c -> c.bestEvidenceId)
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

    private Optional<Resolution> applyPolicy(
            Question q,
            ClusterSummary summary,
            List<ScanLog> scanLogs,
            String model,
            Instant now
    ) {
        // 1. DISPUTED (contradiction)
        if (summary.confirmSum() >= policy.disputeThreshold()
                && summary.denySum() >= policy.disputeThreshold()) {
            return Optional.of(buildResolution(
                    q,
                    QuestionStatus.DISPUTED,
                    DISPUTED_CONFIDENCE,
                    summary,
                    model,
                    String.format(Locale.ROOT,
                            "Conflicting evidence above dispute threshold (confirmSum=%.3f, denySum=%.3f)",
                            summary.confirmSum(), summary.denySum()),
                    now
            ));
        }

        // 2. RESOLVED_YES (quorum met)
        if (summary.independentClusters() >= policy.minConfirmClusters()
                && summary.trustedConfirmClusters() >= policy.minTrustedClusters()
                && summary.confirmSum() >= policy.minConfirmSum()
                && summary.denySum() < policy.maxDenyRatio() * summary.confirmSum()) {
            double yesConfidence = Math.min(MAX_RESOLUTION_CONFIDENCE, summary.confirmSum() / CONFIDENCE_SCALE_SUM);
            return Optional.of(buildResolution(
                    q,
                    QuestionStatus.RESOLVED_YES,
                    yesConfidence,
                    summary,
                    model,
                    String.format(Locale.ROOT,
                            "Confirming quorum met (%d independent clusters, %d trusted, confirmSum=%.3f, denySum=%.3f)",
                            summary.independentClusters(),
                            summary.trustedConfirmClusters(),
                            summary.confirmSum(),
                            summary.denySum()),
                    now
            ));
        }

        // 3. Still before deadline + grace
        if (now.isBefore(q.finalAt())) {
            return Optional.empty();
        }

        // 4. Past deadline + grace
        long totalScans = scanLogs.size();
        long failedScans = scanLogs.stream()
                .filter(s -> s != null && !s.gdeltOk())
                .count();
        double failedRatio = (totalScans == 0) ? 1.0 : (double) failedScans / totalScans;

        if (failedRatio > policy.maxFailedScanRatio()) {
            return Optional.of(buildResolution(
                    q,
                    QuestionStatus.UNRESOLVABLE,
                    0.0,
                    summary,
                    model,
                    "GDELT coverage gap: failed scan ratio=" + failedRatio,
                    now
            ));
        }

        if (summary.confirmSum() >= policy.postDeadlineDisputeConfirmSum()) {
            return Optional.of(buildResolution(
                    q,
                    QuestionStatus.DISPUTED,
                    DISPUTED_CONFIDENCE,
                    summary,
                    model,
                    "Deadline passed with partial evidence below YES quorum",
                    now
            ));
        }

        double noConfidence = Math.max(
                MIN_NO_CONFIDENCE,
                Math.min(MAX_RESOLUTION_CONFIDENCE, 1.0 - (summary.confirmSum() / policy.minConfirmSum()))
        );
        return Optional.of(buildResolution(
                q,
                QuestionStatus.RESOLVED_NO,
                noConfidence,
                summary,
                model,
                String.format(Locale.ROOT,
                        "Deadline passed without confirming quorum (confirmSum=%.3f, denySum=%.3f)",
                        summary.confirmSum(), summary.denySum()),
                now
        ));
    }

    private Resolution buildResolution(
            Question q,
            QuestionStatus outcome,
            double confidence,
            ClusterSummary summary,
            String model,
            String details,
            Instant resolvedAt
    ) {
        return new Resolution(
                q.id(),
                outcome,
                confidence,
                summary.confirmSum(),
                summary.denySum(),
                summary.independentClusters(),
                summary.supportingIds(),
                policy.version(),
                model,
                details,
                resolvedAt
        );
    }

    private static double attributionWeight(String attribution) {
        if (attribution == null) {
            return 0.5;
        }
        return switch (attribution.trim().toLowerCase(Locale.ROOT)) {
            case "official_source_cited" -> 1.0;
            case "direct_reporting" -> 0.9;
            case "anonymous_sources" -> 0.5;
            case "opinion_or_analysis" -> 0.2;
            default -> 0.5;
        };
    }

    private static double freshnessWeight(Evidence ev, Question q, Assessment a) {
        if (ev.publishedAt() != null
                && q.windowStart() != null
                && ev.publishedAt().isBefore(q.windowStart().minus(FRESH_LOOKBACK))) {
            return 0.0;
        }
        return (a.pFresh() >= MIN_FRESH_PROBABILITY) ? 1.0 : a.pFresh();
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String selectModel(List<ScoredItem> scoredItems) {
        return scoredItems.stream()
                .max(Comparator.comparingDouble(ScoredItem::confirmWeight)
                        .thenComparingDouble(ScoredItem::denyWeight)
                        .thenComparing(s -> s.evidence().id(), Comparator.nullsFirst(Comparator.<UUID>reverseOrder())))
                .map(s -> s.assessment().model())
                .filter(m -> m != null && !m.isBlank())
                .orElse("none");
    }

    private record ScoredItem(
            Evidence evidence,
            Assessment assessment,
            double tier,
            double confirmWeight,
            double denyWeight,
            String normalizedTitle,
            String canonicalDomain,
            long simhash
    ) {
        double maxWeight() {
            return Math.max(confirmWeight, denyWeight);
        }

        boolean isTrusted() {
            return tier >= TRUSTED_TIER_THRESHOLD;
        }
    }

    private record ClusterSummary(
            int independentClusters,
            long trustedConfirmClusters,
            double confirmSum,
            double denySum,
            List<UUID> supportingIds
    ) {
    }

    private static final class Cluster {
        private final List<ScoredItem> members = new ArrayList<>();
        private double confirmWeight;
        private double denyWeight;
        private boolean trusted;
        private UUID bestEvidenceId;
        private double bestConfirmWeight = -1.0;

        Cluster(ScoredItem initial) {
            add(initial);
        }

        boolean matches(ScoredItem candidate) {
            for (ScoredItem member : members) {
                if (candidate.simhash() != 0L
                        && member.simhash() != 0L
                        && SimHash.hammingDistance(candidate.simhash(), member.simhash()) <= MAX_SIMHASH_DISTANCE) {
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

        void add(ScoredItem item) {
            members.add(item);
            if (item.confirmWeight() > confirmWeight) {
                confirmWeight = item.confirmWeight();
            }
            if (item.denyWeight() > denyWeight) {
                denyWeight = item.denyWeight();
            }
            if (item.isTrusted()) {
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
    }
}
