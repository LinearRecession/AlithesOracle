package io.alithes.oracle.service.resolution;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.ScoringProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.service.ResolutionEngine;
import io.alithes.oracle.service.SourceTierRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Computes deterministic confirmation and denial evidence weights from Jev probabilities,
 * source credibility tiers, attribution quality, fetch depth, stance stability, and freshness.
 */
@Component
public class EvidenceScorer {

    private static final Comparator<ScoredEvidence> SCORED_ITEM_ORDER =
            Comparator.comparingDouble(ScoredEvidence::maxWeight).reversed()
                    .thenComparing(Comparator.comparingDouble(ScoredEvidence::confirmWeight).reversed())
                    .thenComparing(s -> s.evidence().id(), Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private final ScoringProperties scoringProps;
    private final SourceTierRegistry sourceTierRegistry;

    @Autowired
    public EvidenceScorer(OracleProperties props, SourceTierRegistry sourceTierRegistry) {
        this(Objects.requireNonNull(props, "props must not be null").scoring(), sourceTierRegistry);
    }

    public EvidenceScorer(ScoringProperties scoringProps, SourceTierRegistry sourceTierRegistry) {
        this.scoringProps = Objects.requireNonNull(scoringProps, "scoringProps must not be null");
        this.sourceTierRegistry = Objects.requireNonNull(sourceTierRegistry, "sourceTierRegistry must not be null");
    }

    public List<ScoredEvidence> score(Question q, List<ResolutionEngine.EvidenceWithAssessment> items) {
        Objects.requireNonNull(q, "question must not be null");
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ScoredEvidence> scored = new ArrayList<>(items.size());
        for (ResolutionEngine.EvidenceWithAssessment item : items) {
            if (item == null || item.evidence() == null || item.assessment() == null) {
                continue;
            }
            Evidence ev = item.evidence();
            Assessment a = item.assessment();

            double tier;
            double confirmWeight;
            double denyWeight;
            if (a.injectionFlag() || a.pStanceFits() < scoringProps.minStanceFits()) {
                tier = 0.0;
                confirmWeight = 0.0;
                denyWeight = 0.0;
            } else {
                tier = sourceTierRegistry.weightFor(ev.domain());
                double attr = AttributionTier.fromKey(a.attribution()).weight();
                double fetch = (ev.fetchStatus() == FetchStatus.OK) ? 1.0 : scoringProps.titleOnlyFetchFactor();
                double stability = (a.stanceUnstable() || a.stanceConfidence() < scoringProps.minStanceConfidence())
                        ? scoringProps.unstableStanceFactor()
                        : 1.0;
                double fresh = freshnessWeight(ev, q, a);
                double base = a.pRelevant() * fresh * tier * attr * fetch * stability;
                confirmWeight = base * a.pConfirms();
                denyWeight = base * a.pDenies();
            }

            scored.add(new ScoredEvidence(
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

    public String selectModel(List<ScoredEvidence> scoredItems) {
        return scoredItems.stream()
                .max(Comparator.comparingDouble(ScoredEvidence::confirmWeight)
                        .thenComparingDouble(ScoredEvidence::denyWeight)
                        .thenComparing(s -> s.evidence().id(), Comparator.nullsFirst(Comparator.<UUID>reverseOrder())))
                .map(s -> s.assessment().model())
                .filter(m -> m != null && !m.isBlank())
                .orElse("none");
    }

    private double freshnessWeight(Evidence ev, Question q, Assessment a) {
        Duration freshLookback = Duration.ofHours(scoringProps.freshLookbackHours());
        if (ev.publishedAt() != null
                && q.windowStart() != null
                && ev.publishedAt().isBefore(q.windowStart().minus(freshLookback))) {
            return 0.0;
        }
        return (a.pFresh() >= scoringProps.minFreshProbability()) ? 1.0 : a.pFresh();
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
}
