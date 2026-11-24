package io.alithes.oracle.service;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.ScoringProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.service.resolution.ClusterSummary;
import io.alithes.oracle.service.resolution.EvidenceClusterer;
import io.alithes.oracle.service.resolution.EvidenceScorer;
import io.alithes.oracle.service.resolution.ResolutionContext;
import io.alithes.oracle.service.resolution.ResolutionRule;
import io.alithes.oracle.service.resolution.ResolutionRules;
import io.alithes.oracle.service.resolution.RuleOutcome;
import io.alithes.oracle.service.resolution.ScoredEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic policy engine orchestrating evidence scoring, syndication clustering,
 * and evaluation through an ordered chain of {@link ResolutionRule} components.
 */
@Component
public class ResolutionEngine {

    public record EvidenceWithAssessment(Evidence evidence, Assessment assessment) {
    }

    private final PolicyProperties policy;
    private final ScoringProperties scoring;
    private final EvidenceScorer scorer;
    private final EvidenceClusterer clusterer;
    private final List<ResolutionRule> rules;

    @Autowired
    public ResolutionEngine(
            OracleProperties props,
            EvidenceScorer scorer,
            EvidenceClusterer clusterer,
            List<ResolutionRule> rules
    ) {
        this(
                Objects.requireNonNull(props, "props must not be null").policy(),
                props.scoring(),
                scorer,
                clusterer,
                rules
        );
    }

    public ResolutionEngine(PolicyProperties policy, SourceTierRegistry sourceTierRegistry) {
        this(
                policy,
                ScoringProperties.defaults(),
                new EvidenceScorer(ScoringProperties.defaults(), sourceTierRegistry),
                new EvidenceClusterer(ScoringProperties.defaults()),
                ResolutionRules.defaultChain()
        );
    }

    public ResolutionEngine(
            PolicyProperties policy,
            ScoringProperties scoring,
            EvidenceScorer scorer,
            EvidenceClusterer clusterer,
            List<ResolutionRule> rules
    ) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.scoring = Objects.requireNonNull(scoring, "scoring must not be null");
        this.scorer = Objects.requireNonNull(scorer, "scorer must not be null");
        this.clusterer = Objects.requireNonNull(clusterer, "clusterer must not be null");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
    }

    /**
     * Evaluates all assessed evidence and upstream scan logs for {@code q} at {@code now}.
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

        List<ScoredEvidence> scoredItems = scorer.score(q, safeItems);
        String model = scorer.selectModel(scoredItems);
        ClusterSummary summary = clusterer.clusterAndSummarize(scoredItems, policy);

        ResolutionContext context = new ResolutionContext(
                q, summary, safeScanLogs, policy, scoring, model, now);
        for (ResolutionRule rule : rules) {
            Optional<RuleOutcome> outcome = rule.evaluate(context);
            if (outcome.isPresent()) {
                return switch (outcome.get()) {
                    case RuleOutcome.Resolved resolved -> Optional.of(resolved.resolution());
                    case RuleOutcome.Defer defer -> Optional.empty();
                };
            }
        }
        return Optional.empty();
    }
}
